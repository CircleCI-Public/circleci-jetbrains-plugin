package com.circleci.idea.api.clients

import com.circleci.idea.api.ApiResponse
import com.circleci.idea.api.CircleCIApiClient
import com.circleci.idea.logging.CircleCILogger
import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Base class for specialized CircleCI API clients.
 * Provides shared error handling, parsing, and request execution logic.
 *
 * Each specialized client (Pipeline, Workflow, Job, etc.) extends this base
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
    protected fun <T> executeRequest(
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
     * Execute a POST request with no response body expected.
     *
     * @param client The initialized API client
     * @param path API endpoint path
     * @return Result with Unit or error
     */
    protected fun executePostRequest(
        client: CircleCIApiClient,
        path: String,
    ): Result<Unit> {
        val response = client.post(path)

        return when (response) {
            is ApiResponse.Success -> Result.success(Unit)
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
     * Ensure client is initialized before making API calls.
     */
    protected fun requireClient(client: CircleCIApiClient?): CircleCIApiClient {
        return checkNotNull(client) { "API client not initialized" }
    }
}
