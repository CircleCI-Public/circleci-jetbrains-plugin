package com.circleci.idea.project

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.api.callApi
import com.circleci.idea.project.models.ProjectInfo
import com.circleci.idea.state.CircleCIStateStore
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Where projects' and their organizations' IDs and names are looked up, by
 * project slug or ID. Each is looked up once, however many ask for it at once,
 * and remembered while logged in as the same user to the same host; a failed
 * lookup is tried again next time.
 */
@Service(Service.Level.PROJECT)
class ProjectInfoService(project: Project, scope: CoroutineScope) {
    private val cache =
        ProjectInfoCache(
            scope,
            fetchBySlug = { slug -> callApi(project) { getProject(slug) } },
            fetchById = { id -> callApi(project) { getProjectById(id) } },
        )

    init {
        scope.launch {
            CircleCIStateStore.getInstance(project).auth
                .map { Triple(it.isAuthenticated, it.user?.id, it.hostUrl) }
                .distinctUntilChanged()
                .collect { cache.clear() }
        }
    }

    /** The project [slug] names, e.g. gh/org/repo. */
    suspend fun bySlug(slug: String): Result<ProjectInfo> = cache.bySlug(slug)

    /**
     * The project with the ID [id], by the slug it was looked up by if it
     * was, else by the slug [CircleCIApiService.getProjectById] gives it.
     */
    suspend fun byId(id: String): Result<ProjectInfo> = cache.byId(id)

    companion object {
        fun getInstance(project: Project): ProjectInfoService = project.getService(ProjectInfoService::class.java)
    }
}

/** [ProjectInfoService]'s lookups, made in [scope] so that one caller's cancelling doesn't fail the others'. */
internal class ProjectInfoCache(
    private val scope: CoroutineScope,
    private val fetchBySlug: suspend (String) -> Result<ProjectInfo>,
    private val fetchById: suspend (String) -> Result<ProjectInfo>,
) {
    private val slugs = ConcurrentHashMap<String, Deferred<Result<ProjectInfo>>>()
    private val ids = ConcurrentHashMap<String, Deferred<Result<ProjectInfo>>>()

    // The slug a project was looked up by is the one to give it, over the standalone slug made from its IDs.
    suspend fun bySlug(slug: String): Result<ProjectInfo> =
        lookup(slugs, slug) { fetchBySlug(slug).onSuccess { ids[it.id] = CompletableDeferred(Result.success(it)) } }

    suspend fun byId(id: String): Result<ProjectInfo> =
        lookup(
            ids,
            id,
        ) { fetchById(id).onSuccess { slugs.putIfAbsent(it.slug, CompletableDeferred(Result.success(it))) } }

    fun clear() {
        slugs.clear()
        ids.clear()
    }

    private suspend fun lookup(
        cache: ConcurrentHashMap<String, Deferred<Result<ProjectInfo>>>,
        key: String,
        fetch: suspend () -> Result<ProjectInfo>,
    ): Result<ProjectInfo> {
        val lookup = cache.computeIfAbsent(key) { scope.async { fetch() } }
        return lookup.await().onFailure { cache.remove(key, lookup) }
    }
}
