package com.circleci.idea.toolwindow.settings

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.api.models.Context
import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.settings.CircleCISettings
import com.circleci.idea.state.PagedList
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The settings' API calls, made on IO with the API client set up. They
 * fail, rather than throw, if not logged in or if anything goes wrong.
 */
internal class SettingsApi(project: Project) {
    private val authService = CircleCIAuthService.getInstance(project)
    private val apiService = CircleCIApiService.getInstance()
    private val settings = CircleCISettings.getInstance()

    // The last project whose organization was looked up: its slug, and the org's ID.
    @Volatile
    private var org: Pair<String, String>? = null

    suspend fun <T> call(request: CircleCIApiService.() -> Result<T>): Result<T> =
        withContext(Dispatchers.IO) {
            val token = authService.getToken()
            if (!authService.isAuthenticated() || token == null) {
                return@withContext Result.failure(IllegalStateException("Not logged in to CircleCI"))
            }
            apiService.initialize(token, settings.hostUrl)
            try {
                apiService.request()
            } catch (e: CancellationException) {
                throw e
            } catch (
                // Whatever went wrong, it's a failed request to show, not a crash.
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Result.failure(e)
            }
        }

    /** [request] with the ID of [slug]'s organization, which is looked up once. */
    suspend fun <T> inOrg(
        slug: String,
        request: CircleCIApiService.(orgId: String) -> Result<T>,
    ): Result<T> {
        val orgId =
            org?.takeIf { it.first == slug }?.second
                ?: call { getProjectLink(slug) }
                    .mapCatching { link -> link.orgId ?: error("Project $slug has no organization") }
                    .onSuccess { org = slug to it }
                    .getOrElse { return Result.failure(it) }
        return call { request(orgId) }
    }

    /** The contexts of [slug]'s organization, a page at a time. */
    fun contextPages(slug: String): PagedList<Context, String> =
        PagedList(Context::id) { cursor -> inOrg(slug) { listContexts(it, cursor) } }
}
