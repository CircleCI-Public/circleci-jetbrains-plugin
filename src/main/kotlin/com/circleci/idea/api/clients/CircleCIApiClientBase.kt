package com.circleci.idea.api.clients

import com.circleci.idea.api.ApiResponse
import com.circleci.idea.api.CircleCIApiClient
import com.circleci.idea.logging.CircleCILogger
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Base class for specialized CircleCI API clients.
 * Provides shared error handling, parsing, and request execution logic.
 *
 * Each specialized client (Run, Workflow, Job, etc.) extends this base
 * to implement domain-specific API methods while inheriting common functionality.
 */
abstract class CircleCIApiClientBase {
    protected val logger: CircleCILogger = CircleCILogger.getInstance()
    protected val gson: Gson = Gson()

    /**
     * Execute a GET or POST request with typed response parsing.
     *
     * @param client The initialized API client
     * @param path API endpoint path
     * @param params Query parameters for GET requests
     * @param body Request body for POST requests
     * @param parser Function to parse JSON response into domain type
     * @return Result with parsed data or error
     */
    protected suspend fun <T> executeRequest(
        client: CircleCIApiClient,
        path: String,
        params: Map<String, String> = emptyMap(),
        body: Any? = null,
        parser: (JsonObject) -> T,
    ): Result<T> {
        val response =
            if (body != null) {
                client.post(path, body)
            } else {
                client.get(path, params)
            }

        return when (response) {
            is ApiResponse.Success -> {
                try {
                    Result.success(parser(response.data))
                } catch (e: Exception) {
                    logger.error("Failed to parse API response: ${e.message}", e)
                    Result.failure(Exception("Failed to parse response: ${e.message}"))
                }
            }
            else -> failure(response)
        }
    }

    /**
     * Execute a POST request with no response body expected.
     *
     * @param client The initialized API client
     * @param path API endpoint path
     * @param body Request body, or none for an empty JSON object
     * @return Result with Unit or error
     */
    protected suspend fun executePostRequest(
        client: CircleCIApiClient,
        path: String,
        body: Any? = null,
    ): Result<Unit> = unitResult(client.post(path, body))

    /**
     * Execute a DELETE request with no response body expected.
     */
    protected suspend fun executeDeleteRequest(
        client: CircleCIApiClient,
        path: String,
        params: Map<String, String> = emptyMap(),
    ): Result<Unit> = unitResult(client.delete(path, params))

    private fun unitResult(response: ApiResponse): Result<Unit> =
        if (response is ApiResponse.Success) Result.success(Unit) else failure(response)

    private fun <T> failure(response: ApiResponse): Result<T> {
        return when (response) {
            is ApiResponse.Success -> error("Not a failure")
            is ApiResponse.Error -> {
                logger.error("API error: ${response.message}")
                Result.failure(Exception(response.message))
            }
            is ApiResponse.Unauthorized -> {
                logger.error("API unauthorized: ${response.message}")
                Result.failure(Exception("Unauthorized: ${response.message}"))
            }
            is ApiResponse.RateLimited -> {
                logger.warn("API rate limited. Retry after ${response.retryAfter}s")
                Result.failure(Exception("Rate limited. Retry after ${response.retryAfter}s"))
            }
        }
    }

    /**
     * Every item of a paged list: [fetch] gets each page from the previous
     * page's cursor (null for the first).
     */
    protected suspend fun <T> fetchAllPages(fetch: suspend (String?) -> Result<V3Page<T>>): Result<List<T>> {
        val items = mutableListOf<T>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val page = fetch(cursor).getOrElse { return Result.failure(it) }
            items.addAll(page.items)
            cursor = page.nextCursor ?: return Result.success(items)
        }
        logger.warn("Stopped after $MAX_PAGES pages")
        return Result.success(items)
    }

    /** A timestamp the API sent, or null if it sent none, or one that isn't one. */
    protected fun instant(value: String?): Instant? =
        value?.takeIf { it.isNotEmpty() }?.let {
            try {
                Instant.parse(it)
            } catch (_: DateTimeParseException) {
                null
            }
        }

    /**
     * Ensure client is initialized before making API calls.
     */
    protected fun requireClient(client: CircleCIApiClient?): CircleCIApiClient {
        return checkNotNull(client) { "API client not initialized" }
    }

    private companion object {
        // A guard against a server that never stops handing out cursors.
        const val MAX_PAGES = 20
    }
}
