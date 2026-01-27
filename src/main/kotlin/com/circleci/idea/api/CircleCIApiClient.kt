package com.circleci.idea.api

import com.circleci.idea.logging.CircleCILogger
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * HTTP client for CircleCI API v2.
 *
 * Features:
 * - Configurable base URL for cloud/server
 * - Authentication via Circle-Token header
 * - Retry logic with exponential backoff for 429 rate limits
 * - Request deduplication for in-flight requests
 * - Client-side rate limiting
 * - Automatic error handling and parsing
 */
class CircleCIApiClient(
    private val baseUrl: String = "https://circleci.com",
    private val token: String,
    private val userAgent: String = "CircleCI-IntelliJ-Plugin/1.0.0",
) {
    private val gson = Gson()
    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()
    private val logger = CircleCILogger.getInstance()

    // In-flight request deduplication
    private val inFlightRequests = ConcurrentHashMap<String, Call>()

    // Rate limiter
    private val rateLimiter = RateLimiter(maxRequestsPerSecond = 50)

    // OkHttp client with timeouts
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(token, userAgent))
            .addInterceptor(RetryInterceptor(maxRetries = 3))
            .build()

    /**
     * Execute a GET request.
     */
    fun get(
        path: String,
        queryParams: Map<String, String> = emptyMap(),
    ): ApiResponse {
        val url = buildUrl(path, queryParams)
        val request =
            Request.Builder()
                .url(url)
                .get()
                .build()

        return executeRequest(request)
    }

    /**
     * Execute a POST request.
     */
    fun post(
        path: String,
        body: Any? = null,
    ): ApiResponse {
        val url = buildUrl(path)
        val jsonBody =
            if (body != null) {
                gson.toJson(body).toRequestBody(mediaTypeJson)
            } else {
                "{}".toRequestBody(mediaTypeJson)
            }

        val request =
            Request.Builder()
                .url(url)
                .post(jsonBody)
                .build()

        return executeRequest(request)
    }

    /**
     * Execute a PUT request.
     */
    fun put(
        path: String,
        body: Any,
    ): ApiResponse {
        val url = buildUrl(path)
        val jsonBody = gson.toJson(body).toRequestBody(mediaTypeJson)

        val request =
            Request.Builder()
                .url(url)
                .put(jsonBody)
                .build()

        return executeRequest(request)
    }

    /**
     * Execute a DELETE request.
     */
    fun delete(path: String): ApiResponse {
        val url = buildUrl(path)
        val request =
            Request.Builder()
                .url(url)
                .delete()
                .build()

        return executeRequest(request)
    }

    /**
     * Execute request with deduplication and rate limiting.
     */
    private fun executeRequest(request: Request): ApiResponse {
        val requestKey = "${request.method} ${request.url}"
        val startTime = System.currentTimeMillis()

        // Log API request
        logger.logApiRequest(request.method, request.url.toString())

        // Check for in-flight duplicate request
        val existingCall = inFlightRequests[requestKey]
        if (existingCall != null && !existingCall.isCanceled()) {
            logger.debug("Deduplicating in-flight request: $requestKey")
            // Wait for existing request to complete
            return try {
                val response = existingCall.execute()
                parseResponse(response, request, startTime)
            } catch (e: IOException) {
                logger.error("Network error for deduplicated request: ${e.message}", e)
                ApiResponse.Error(e.message ?: "Network error", 0)
            }
        }

        // Apply rate limiting
        rateLimiter.acquire()

        // Execute new request
        val call = client.newCall(request)
        inFlightRequests[requestKey] = call

        return try {
            val response = call.execute()
            parseResponse(response, request, startTime)
        } catch (e: IOException) {
            logger.logApiError(request.method, request.url.toString(), 0, e.message ?: "Network error")
            ApiResponse.Error(e.message ?: "Network error", 0)
        } finally {
            inFlightRequests.remove(requestKey)
        }
    }

    /**
     * Parse HTTP response into ApiResponse.
     */
    private fun parseResponse(
        response: Response,
        request: Request,
        startTime: Long,
    ): ApiResponse {
        val code = response.code
        val body = response.body?.string() ?: ""
        val duration = System.currentTimeMillis() - startTime

        // Log response
        logger.logApiResponse(request.method, request.url.toString(), code, duration)

        return when {
            response.isSuccessful -> {
                try {
                    val json = gson.fromJson(body, JsonObject::class.java)
                    ApiResponse.Success(json, code)
                } catch (e: Exception) {
                    logger.warn("Failed to parse JSON response: ${e.message}")
                    ApiResponse.Success(JsonObject(), code)
                }
            }
            code == 401 -> {
                logger.warn("Authentication failed: Invalid or expired token")
                ApiResponse.Unauthorized("Invalid or expired token")
            }
            code == 429 -> {
                val retryAfter = response.header("Retry-After")?.toIntOrNull() ?: 1
                logger.warn("Rate limited. Retry after: $retryAfter seconds")
                ApiResponse.RateLimited(retryAfter)
            }
            else -> {
                val errorMessage =
                    try {
                        val json = gson.fromJson(body, JsonObject::class.java)
                        json.get("message")?.asString ?: "Request failed"
                    } catch (e: Exception) {
                        "Request failed: $body"
                    }
                logger.logApiError(request.method, request.url.toString(), code, errorMessage)
                ApiResponse.Error(errorMessage, code)
            }
        }
    }

    /**
     * Build full URL with base URL and query parameters.
     */
    private fun buildUrl(
        path: String,
        queryParams: Map<String, String> = emptyMap(),
    ): String {
        val cleanPath = path.trimStart('/')
        val url = "$baseUrl/$cleanPath"

        return if (queryParams.isEmpty()) {
            url
        } else {
            val params =
                queryParams.entries.joinToString("&") { (key, value) ->
                    "$key=$value"
                }
            "$url?$params"
        }
    }
}

/**
 * Authentication interceptor that adds Circle-Token header.
 */
private class AuthInterceptor(
    private val token: String,
    private val userAgent: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request =
            chain.request().newBuilder()
                .header("Circle-Token", token)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .build()

        return chain.proceed(request)
    }
}

/**
 * Retry interceptor with exponential backoff for 429 errors.
 */
private class RetryInterceptor(private val maxRetries: Int = 3) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        var retryCount = 0

        while (response.code == 429 && retryCount < maxRetries) {
            val retryAfter = response.header("Retry-After")?.toIntOrNull() ?: 1
            val backoffDelay = min(2.0.pow(retryCount).toLong(), retryAfter.toLong())

            response.close()
            Thread.sleep(backoffDelay * 1000)

            request = chain.request()
            response = chain.proceed(request)
            retryCount++
        }

        return response
    }
}

/**
 * Simple token bucket rate limiter.
 */
private class RateLimiter(private val maxRequestsPerSecond: Int) {
    private var tokens = maxRequestsPerSecond
    private var lastRefill = System.currentTimeMillis()
    private val lock = Any()

    fun acquire() {
        synchronized(lock) {
            refillTokens()

            while (tokens <= 0) {
                Thread.sleep(10)
                refillTokens()
            }

            tokens--
        }
    }

    private fun refillTokens() {
        val now = System.currentTimeMillis()
        val elapsedSeconds = (now - lastRefill) / 1000.0

        if (elapsedSeconds >= 1.0) {
            tokens = min(maxRequestsPerSecond, tokens + maxRequestsPerSecond)
            lastRefill = now
        }
    }
}

/**
 * API response sealed class.
 */
sealed class ApiResponse {
    data class Success(val data: JsonObject, val code: Int) : ApiResponse()

    data class Error(val message: String, val code: Int) : ApiResponse()

    data class Unauthorized(val message: String) : ApiResponse()

    data class RateLimited(val retryAfter: Int) : ApiResponse()
}
