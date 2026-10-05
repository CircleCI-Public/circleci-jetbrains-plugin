package com.circleci.idea.toolwindow.settings

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.api.callApi
import com.circleci.idea.api.models.Context
import com.circleci.idea.project.ProjectInfoService
import com.circleci.idea.state.PagedList
import com.intellij.openapi.project.Project

/**
 * The settings' API calls, made on IO with the API client set up. They
 * fail, rather than throw, if not logged in or if anything goes wrong.
 */
internal class SettingsApi(private val project: Project) {
    private val projects = ProjectInfoService.getInstance(project)

    suspend fun <T> call(request: CircleCIApiService.() -> Result<T>): Result<T> = callApi(project, request)

    /** [request] with the ID of [slug]'s organization. */
    suspend fun <T> inOrg(
        slug: String,
        request: CircleCIApiService.(orgId: String) -> Result<T>,
    ): Result<T> {
        val orgId = projects.bySlug(slug).getOrElse { return Result.failure(it) }.org.id
        return call { request(orgId) }
    }

    /** The contexts of [slug]'s organization, a page at a time. */
    fun contextPages(slug: String): PagedList<Context, String> =
        PagedList(Context::id) { cursor -> inOrg(slug) { listContexts(it, cursor) } }
}
