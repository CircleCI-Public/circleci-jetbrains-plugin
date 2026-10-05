package com.circleci.idea.api

import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.settings.CircleCISettings
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [request], made on IO with the API client set up for [project]'s login.
 * It fails, rather than throws, if not logged in or if anything goes wrong.
 */
suspend fun <T> callApi(
    project: Project,
    request: CircleCIApiService.() -> Result<T>,
): Result<T> =
    withContext(Dispatchers.IO) {
        val authService = CircleCIAuthService.getInstance(project)
        val token = authService.getToken()
        if (!authService.isAuthenticated() || token == null) {
            return@withContext Result.failure(IllegalStateException("Not logged in to CircleCI"))
        }
        val apiService = CircleCIApiService.getInstance()
        apiService.initialize(token, CircleCISettings.getInstance().hostUrl)
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
