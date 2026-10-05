package com.circleci.idea.context

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.api.clients.V3Page
import com.circleci.idea.api.models.ContextDetail
import com.circleci.idea.api.models.ContextRestriction
import com.circleci.idea.api.models.EnvVar
import com.circleci.idea.api.models.NamedEntity
import com.circleci.idea.api.models.RestrictionType
import com.circleci.idea.api.withChangeProgress
import com.circleci.idea.project.ProjectInfoService
import com.circleci.idea.toolwindow.settings.Loadable
import com.circleci.idea.toolwindow.settings.SettingsApi
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a context page shows: the context, its environment variables, and its restrictions. */
data class ContextPageState(
    val context: Loadable<ContextDetail> = Loadable.NotLoaded,
    val envVars: Loadable<List<EnvVar>> = Loadable.NotLoaded,
    val restrictions: Loadable<List<ContextRestriction>> = Loadable.NotLoaded,
) {
    /** The context's restrictions of one type, as they're listed. */
    fun restrictions(type: RestrictionType): Loadable<List<ContextRestriction>> =
        when (restrictions) {
            is Loadable.Loaded -> Loadable.Loaded(restrictions.value.filter { it.type == type })
            else -> restrictions
        }
}

/**
 * A context page's data, and the changes it makes to the context.
 *
 * Everything loads as the page opens and on [refresh]; a list loads again
 * after a change to it, and the environment variables after a change made
 * elsewhere ([ContextPages.envVarChanges]). [scope] must run on the EDT, where the state
 * changes; the API calls switch to IO.
 */
class ContextPageModel(
    private val project: Project,
    private val ref: ContextRef,
    private val scope: CoroutineScope,
) {
    private val api = SettingsApi(project)
    private val pages = ContextPages.getInstance(project)
    private val projects = ProjectInfoService.getInstance(project)

    private val _state = MutableStateFlow(ContextPageState())
    val state: StateFlow<ContextPageState> = _state.asStateFlow()

    private val requests = Requests()

    /** How many requests are in flight. */
    val loads: StateFlow<Int> = requests.count

    init {
        refresh()
        scope.launch {
            pages.envVarChanges.collect { change ->
                if (change.contextId == ref.id && change.source !== this@ContextPageModel) loadEnvVars()
            }
        }
    }

    /** Load everything again, leaving what's shown in place until it's done. */
    fun refresh() {
        loadContext()
        loadEnvVars()
        loadRestrictions()
    }

    /** Add an environment variable, or replace its value; then list them again. */
    suspend fun setEnvVar(
        name: String,
        value: String,
    ): Result<Unit> = changeEnvVars("Saving environment variable $name") { setContextEnvVar(ref.id, name, value) }

    suspend fun deleteEnvVar(name: String): Result<Unit> =
        changeEnvVars("Deleting environment variable $name") { deleteContextEnvVar(ref.id, name) }

    /** Delete the context; its page closes once it's gone. */
    suspend fun deleteContext(): Result<Unit> =
        withChangeProgress(project, "Deleting context ${ref.name}") { api.call { deleteContext(ref.id) } }
            .onSuccess { pages.contextDeleted(ContextDeleted(ref.id, this)) }

    /** Restrict the context, by a group's or project's ID or an expression; then list its restrictions again. */
    suspend fun addRestriction(
        type: RestrictionType,
        value: String,
    ): Result<Unit> =
        api.change(
            project,
            "Adding restriction",
            { createContextRestriction(ref.id, type, value) },
            ::loadRestrictions,
        )

    suspend fun deleteRestriction(restriction: ContextRestriction): Result<Unit> =
        api.change(
            project,
            "Removing restriction",
            { deleteContextRestriction(ref.id, restriction.id) },
            ::loadRestrictions,
        )

    /** The ID of the context's organization, which is also the group of all its members. */
    suspend fun orgId(): Result<String> = requests.counted { projects.bySlug(ref.projectSlug) }.map { it.org.id }

    /** The organization's groups, to restrict the context to. */
    suspend fun groups(): Result<List<NamedEntity>> {
        val orgId = orgId().getOrElse { return Result.failure(it) }
        return requests.counted { api.call { listGroups(orgId) } }
    }

    /** A page of the organization's projects whose names contain [name], to restrict the context to. */
    suspend fun searchProjects(
        name: String,
        cursor: String? = null,
    ): Result<V3Page<NamedEntity>> {
        val orgId = orgId().getOrElse { return Result.failure(it) }
        return api.call { searchProjects(orgId, name, cursor) }
    }

    private suspend fun changeEnvVars(
        title: String,
        request: CircleCIApiService.() -> Result<Unit>,
    ): Result<Unit> =
        api.change(project, title, request) {
            loadEnvVars()
            pages.envVarsChanged(EnvVarsChanged(ref.id, this))
        }

    private fun loadContext() {
        load({ api.call { getContext(ref.id) } }) { it.copy(context = keep(it.context, this)) }
    }

    private fun loadEnvVars() {
        load({ api.call { listContextEnvVars(ref.id) } }) { it.copy(envVars = keep(it.envVars, this)) }
    }

    private fun loadRestrictions() {
        load({ api.call { listContextRestrictions(ref.id) } }) { it.copy(restrictions = keep(it.restrictions, this)) }
    }

    /**
     * Fetch something the page shows, and [set] it in the state: as
     * loading first, unless it's shown already, then as it loaded.
     */
    private fun <T> load(
        fetch: suspend () -> Result<T>,
        set: Loadable<T>.(ContextPageState) -> ContextPageState,
    ) {
        _state.update { Loadable.Loading.set(it) }
        scope.launch {
            val loaded =
                requests.counted { fetch() }.fold(
                    { Loadable.Loaded(it) },
                    { Loadable.Failed(it.message ?: "Failed to load") },
                )
            _state.update { loaded.set(it) }
        }
    }
}

/** The requests in flight. */
private class Requests {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    /** [block], counted until it ends, however it ends. */
    suspend fun <T> counted(block: suspend () -> T): T {
        _count.update { it + 1 }
        try {
            return block()
        } finally {
            _count.update { it - 1 }
        }
    }
}

/**
 * [request], under the IDE's progress indicator titled [title], then
 * [reload] what it changed, whether or not it worked: a failure may still
 * have changed something.
 */
private suspend fun SettingsApi.change(
    project: Project,
    title: String,
    request: CircleCIApiService.() -> Result<Unit>,
    reload: () -> Unit,
): Result<Unit> = withChangeProgress(project, title) { call(request) }.also { reload() }

/** [next], except that while it's still loading, what's shown stays. */
private fun <T> keep(
    shown: Loadable<T>,
    next: Loadable<T>,
): Loadable<T> = if (next == Loadable.Loading && shown is Loadable.Loaded) shown else next
