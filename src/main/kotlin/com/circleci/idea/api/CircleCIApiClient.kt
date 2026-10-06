package com.circleci.idea.api

import com.circleci.idea.logging.CircleCILogger
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * HTTP client for the CircleCI API (v3, and v2 where v3 has no equivalent yet).
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
    val baseUrl: String = "https://circleci.com",
    val token: String,
    private val userAgent: String = "CircleCI-IntelliJ-Plugin/1.0.0",
    // One connection pool, shared with every other client
    private val http: HttpClient = CircleCIHttpClient.getInstance().client,
) {
    private val gson = Gson()
    private val logger = CircleCILogger.getInstance()

    // In-flight GETs by URL, each to be shared with identical ones; null if it didn't complete.
    private val inFlightRequests = ConcurrentHashMap<String, CompletableDeferred<ApiResponse?>>()

    // Rate limiter
    private val rateLimiter = RateLimiter(maxRequestsPerSecond = 50)

    /**
     * Execute a GET request.
     */
    suspend fun get(
        path: String,
        queryParams: Map<String, String> = emptyMap(),
    ): ApiResponse = executeRequest(request(buildUrl(path, queryParams)).GET().build())

    /**
     * Execute a GET request for a raw (non-JSON) body, such as step output.
     * Any HTTP status is a success here except 401, so the caller can decide
     * what a 404 means; only network errors and 401 fail.
     *
     * @param path An API path, or an absolute URL the API handed out (an artifact's, say)
     * @param maxBytes Read at most this much of the body, marking the response truncated if there was more
     */
    suspend fun getBytes(
        path: String,
        queryParams: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        maxBytes: Long? = null,
    ): Result<RawResponse> {
        val request =
            request(buildUrl(path, queryParams))
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .GET()
                .build()

        logger.logApiRequest(request.method(), request.uri().toString())
        rateLimiter.acquire()

        return try {
            respond(request).use { response ->
                if (response.statusCode() == 401) {
                    Result.failure(Exception("Unauthorized: Invalid or expired token"))
                } else {
                    val (body, truncated) = readBody(response.body(), maxBytes)
                    Result.success(RawResponse(response.statusCode(), body, response.headers(), truncated))
                }
            }
        } catch (e: IOException) {
            logger.logApiError(request.method(), request.uri().toString(), 0, e.message ?: "Network error")
            Result.failure(e)
        }
    }

    /**
     * A GET whose body [read] takes as it arrives, given the status and the
     * body (empty without one), rather than held whole. As with [getBytes],
     * only 401, network errors and what [read] throws fail.
     */
    suspend fun <T> getStreaming(
        path: String,
        read: (code: Int, body: Reader) -> T,
    ): Result<T> {
        val request = request(buildUrl(path)).GET().build()
        logger.logApiRequest(request.method(), request.uri().toString())
        rateLimiter.acquire()

        return try {
            respond(request).use { response ->
                if (response.statusCode() == 401) {
                    Result.failure(Exception("Unauthorized: Invalid or expired token"))
                } else {
                    runCatching { read(response.statusCode(), response.body().reader()) }
                }
            }
        } catch (e: IOException) {
            logger.logApiError(request.method(), request.uri().toString(), 0, e.message ?: "Network error")
            Result.failure(e)
        }
    }

    /**
     * Download [url] (an absolute URL the API handed out) to [target],
     * streaming rather than holding it in memory. Returns the bytes written.
     */
    suspend fun download(
        url: String,
        target: Path,
    ): Result<Long> {
        val request = request(buildUrl(url)).GET().build()
        logger.logApiRequest(request.method(), request.uri().toString())
        rateLimiter.acquire()

        return try {
            respond(request).use { response ->
                if (response.statusCode() !in HTTP_SUCCESS) {
                    return Result.failure(IOException("HTTP ${response.statusCode()} downloading $url"))
                }
                Files.createDirectories(target.parent)
                Files.newOutputStream(target).use { out -> Result.success(response.body().copyTo(out)) }
            }
        } catch (e: IOException) {
            logger.logApiError(request.method(), request.uri().toString(), 0, e.message ?: "Network error")
            Result.failure(e)
        }
    }

    /**
     * [request]'s response, sent again while it's rate limited (429), up to
     * [MAX_RETRIES] times, after the longer of an exponential backoff and
     * the server's Retry-After. A wait longer than [MAX_RETRY_WAIT_SECONDS]
     * gives up instead. Cancelling the coroutine cancels the request.
     */
    private suspend fun respond(request: HttpRequest): HttpResponse<InputStream> {
        var retries = 0
        while (true) {
            val response = http.sendAsync(request, BodyHandlers.ofInputStream()).await()
            if (response.statusCode() != HTTP_TOO_MANY_REQUESTS || retries == MAX_RETRIES) return response
            val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 1
            val wait = max(1L shl retries, retryAfter)
            if (wait > MAX_RETRY_WAIT_SECONDS) return response
            response.body().close()
            logger.debug("Rate limited, retrying in ${wait}s: ${request.method()} ${request.uri()}")
            delay(wait * MS_PER_SECOND)
            retries++
        }
    }

    /**
     * Execute a POST request.
     */
    suspend fun post(
        path: String,
        body: Any? = null,
    ): ApiResponse {
        val json = if (body != null) gson.toJson(body) else "{}"
        return executeRequest(request(buildUrl(path)).jsonBody().POST(BodyPublishers.ofString(json)).build())
    }

    /**
     * Execute a PUT request.
     */
    suspend fun put(
        path: String,
        body: Any,
    ): ApiResponse =
        executeRequest(request(buildUrl(path)).jsonBody().PUT(BodyPublishers.ofString(gson.toJson(body))).build())

    /**
     * Execute a DELETE request.
     */
    suspend fun delete(
        path: String,
        queryParams: Map<String, String> = emptyMap(),
    ): ApiResponse = executeRequest(request(buildUrl(path, queryParams)).DELETE().build())

    /**
     * Execute request with deduplication and rate limiting.
     *
     * Concurrent identical GETs share the first one's response: a
     * duplicate waits on its result rather than sending the request again,
     * and sends its own if the first was cancelled. Other methods aren't
     * idempotent, so each one is sent.
     */
    private suspend fun executeRequest(request: HttpRequest): ApiResponse {
        if (request.method() != "GET") {
            return send(request)
        }

        val requestKey = request.uri().toString()
        val pending = CompletableDeferred<ApiResponse?>()
        val inFlight = inFlightRequests.putIfAbsent(requestKey, pending)
        if (inFlight != null) {
            logger.debug("Deduplicating in-flight request: GET $requestKey")
            return inFlight.await() ?: send(request)
        }

        return try {
            send(request).also { pending.complete(it) }
        } finally {
            pending.complete(null)
            inFlightRequests.remove(requestKey, pending)
        }
    }

    private suspend fun send(request: HttpRequest): ApiResponse {
        val startTime = System.currentTimeMillis()

        // Log API request
        logger.logApiRequest(request.method(), request.uri().toString())

        // Apply rate limiting
        rateLimiter.acquire()

        return try {
            respond(request).use { response -> parseResponse(response, request, startTime) }
        } catch (e: IOException) {
            logger.logApiError(request.method(), request.uri().toString(), 0, e.message ?: "Network error")
            ApiResponse.Error(e.message ?: "Network error", 0)
        }
    }

    /**
     * Parse HTTP response into ApiResponse.
     */
    private fun parseResponse(
        response: HttpResponse<InputStream>,
        request: HttpRequest,
        startTime: Long,
    ): ApiResponse {
        val code = response.statusCode()
        val duration = System.currentTimeMillis() - startTime
        val successful = code in HTTP_SUCCESS

        // Log response
        logger.logApiResponse(request.method(), request.uri().toString(), code, duration)

        // A success is parsed as it's read; the rest are small.
        val body = if (successful) "" else response.body().reader().readText()
        return when {
            successful -> {
                try {
                    val json = gson.fromJson(response.body().reader(), JsonObject::class.java)
                    ApiResponse.Success(json ?: JsonObject(), code)
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
                        json.get("message")?.asString ?: v3ErrorMessage(json) ?: "Request failed"
                    } catch (e: Exception) {
                        logger.warn("Failed to parse error response JSON", e)
                        "Request failed: $body"
                    }
                logger.logApiError(request.method(), request.uri().toString(), code, errorMessage)
                ApiResponse.Error(errorMessage, code)
            }
        }
    }

    /** A V3 error's `{"error": {"title": "Title.", "detail": "..."}}`, as one line: "Title: detail". */
    private fun v3ErrorMessage(json: JsonObject): String? {
        val error = json.get("error")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null

        fun text(key: String): String? =
            error.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotEmpty)
        val title = text("title")
        val detail = text("detail") ?: return title
        return title?.let { "${it.removeSuffix(".")}: $detail" } ?: detail
    }

    /** A request to [url], authenticated, with the timeout for its response to start. */
    private fun request(url: URI): HttpRequest.Builder =
        HttpRequest.newBuilder(url)
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header("Circle-Token", token)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")

    /**
     * Build full URL with base URL and query parameters.
     */
    private fun buildUrl(
        path: String,
        queryParams: Map<String, String> = emptyMap(),
    ): URI {
        val absolute = path.startsWith("https://") || path.startsWith("http://")
        val url = if (absolute) path else "$baseUrl/${path.trimStart('/')}"
        // Encoded here, as filter values carry characters such as "/" in branch names.
        val query = queryParams.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val separator = if ('?' in url) "&" else "?"
        return URI(escapeIllegal(if (query.isEmpty()) url else "$url$separator$query"))
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val MAX_RETRIES = 3
        const val MAX_RETRY_WAIT_SECONDS = 60L
        const val MS_PER_SECOND = 1000L
        const val TIMEOUT_SECONDS = 30L
        val HTTP_SUCCESS = 200..299

        // The characters a URI may hold as they are; '%' too, for what's already encoded.
        const val URI_CHARACTERS = "-._~:/?#[]@!$&'()*+,;=%"

        /** [text] as a query parameter's name or value: "a b/c" is "a%20b%2Fc". */
        fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

        /** [url] with what a URI can't hold (a space in an artifact's path, say) percent-encoded. */
        fun escapeIllegal(url: String): String =
            buildString {
                for (byte in url.toByteArray(Charsets.UTF_8)) {
                    val c = (byte.toInt() and 0xFF).toChar()
                    if (c < '\u0080' && (c.isLetterOrDigit() || c in URI_CHARACTERS)) {
                        append(c)
                    } else {
                        append('%').append("%02X".format(byte.toInt() and 0xFF))
                    }
                }
            }
    }
}

/** [block]'s result with the response, its body closed after (which frees the connection). */
private inline fun <T> HttpResponse<InputStream>.use(block: (HttpResponse<InputStream>) -> T): T =
    body().use { block(this) }

private fun HttpResponse<*>.header(name: String): String? = headers().firstValue(name).orElse(null)

private fun HttpRequest.Builder.jsonBody(): HttpRequest.Builder =
    header(
        "Content-Type",
        "application/json; charset=utf-8",
    )

/** At most [maxBytes] of [body] (all of it without a limit), and whether there was more. */
private fun readBody(
    body: InputStream,
    maxBytes: Long?,
): Pair<ByteArray, Boolean> {
    if (maxBytes == null) return body.readBytes() to false
    // Ask for one byte more than the limit: getting it means there was more.
    val bytes = body.readNBytes(min(maxBytes + 1, Int.MAX_VALUE.toLong()).toInt())
    val complete = bytes.size <= maxBytes
    return (if (complete) bytes else bytes.copyOf(maxBytes.toInt())) to !complete
}

/**
 * Simple token bucket rate limiter.
 */
private class RateLimiter(private val maxRequestsPerSecond: Int) {
    private var tokens = maxRequestsPerSecond
    private var lastRefill = System.currentTimeMillis()
    private val lock = Mutex()

    suspend fun acquire() {
        lock.withLock {
            refillTokens()

            while (tokens <= 0) {
                delay(10)
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
 * A raw HTTP response: its status, body bytes and headers.
 */
class RawResponse(
    val code: Int,
    val body: ByteArray,
    val headers: HttpHeaders,
    // The body was cut short at the caller's limit.
    val truncated: Boolean = false,
) {
    val isSuccessful: Boolean
        get() = code in HTTP_SUCCESS

    /** The [name] header's first value, if it has one. */
    fun header(name: String): String? = headers.firstValue(name).orElse(null)

    private companion object {
        val HTTP_SUCCESS = 200..299
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
