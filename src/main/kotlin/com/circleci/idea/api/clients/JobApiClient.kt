package com.circleci.idea.api.clients

import com.circleci.idea.api.CircleCIApiClient
import com.circleci.idea.api.RawResponse
import com.circleci.idea.api.models.ArtifactWire
import com.circleci.idea.api.models.JobDetailWire
import com.circleci.idea.api.models.ResourceUsageWire
import com.circleci.idea.api.models.TestResultWire
import com.circleci.idea.api.models.V3Entity
import com.circleci.idea.api.models.V3List
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.Reader

/**
 * A read of a step's output: the new bytes, and whether the output has
 * finished ([terminal]). [more] says the read stopped at its limit, with
 * more to read already.
 */
class StepOutputChunk(val data: ByteArray, val terminal: Boolean, val more: Boolean = false)

/**
 * API client for job-related operations.
 * Handles job details, step output, tests and artifacts, and cancelling jobs.
 */
class JobApiClient : CircleCIApiClientBase() {
    /**
     * Cancel a job. V3 has no job cancel endpoint yet, so this stays on V2.
     *
     * @param client The initialized API client
     * @param projectSlug Project slug
     * @param jobNumber Job number
     * @return Success or error
     */
    suspend fun cancelJob(
        client: CircleCIApiClient,
        projectSlug: String,
        jobNumber: Long,
    ): Result<Unit> {
        return executePostRequest(client, "/api/v2/project/$projectSlug/job/$jobNumber/cancel")
    }

    /** A job's artifacts, via GET /api/v3/jobs/{id}/artifacts. */
    suspend fun getJobArtifacts(
        client: CircleCIApiClient,
        jobId: String,
    ): Result<List<ArtifactWire>> {
        return executeRequest(client, "/api/v3/jobs/$jobId/artifacts") { data ->
            gson.fromJson<V3List<ArtifactWire>>(data, object : TypeToken<V3List<ArtifactWire>>() {}.type).data.orEmpty()
        }
    }

    /**
     * Read up to [maxBytes] of an artifact, to view it. The response is
     * marked truncated when the artifact is bigger.
     */
    suspend fun readArtifact(
        client: CircleCIApiClient,
        url: String,
        maxBytes: Long,
    ): Result<RawResponse> {
        return client.getBytes(url, maxBytes = maxBytes).mapCatching {
            if (it.isSuccessful) it else error("Failed to read artifact: HTTP ${it.code}")
        }
    }

    /**
     * A job's CPU and memory usage, via GET /api/v3/jobs/{id}/resource-usage;
     * null when it recorded none (an approval job, or one canceled before it ran).
     */
    suspend fun getJobResourceUsage(
        client: CircleCIApiClient,
        jobId: String,
    ): Result<ResourceUsageWire?> {
        return client.getBytes("/api/v3/jobs/$jobId/resource-usage").mapCatching { response ->
            when {
                response.isSuccessful -> {
                    val type = object : TypeToken<V3Entity<ResourceUsageWire>>() {}.type
                    gson.fromJson<V3Entity<ResourceUsageWire>>(String(response.body, Charsets.UTF_8), type).data
                }
                response.code == HTTP_NOT_FOUND -> null
                else -> error("Failed to read resource usage: HTTP ${response.code}")
            }
        }
    }

    /** A job with its steps, via GET /api/v3/jobs/{id}. */
    suspend fun getJob(
        client: CircleCIApiClient,
        jobId: String,
    ): Result<JobDetailWire> {
        return executeRequest(client, "/api/v3/jobs/$jobId") { data ->
            gson.fromJson<V3Entity<JobDetailWire>>(data, object : TypeToken<V3Entity<JobDetailWire>>() {}.type).data
                ?: error("No job found for $jobId")
        }
    }

    /**
     * A step's stdout from byte [offset] on, up to [STDOUT_READ_BYTES] of it,
     * and whether it has finished (the X-Terminal header). A step with no
     * output yet reads as empty.
     */
    suspend fun getStepStdout(
        client: CircleCIApiClient,
        jobId: String,
        execution: Int,
        stepNum: Int,
        offset: Long,
    ): Result<StepOutputChunk> {
        val headers = mapOf("Range" to "bytes=$offset-")
        val params = stepParams(execution, stepNum)
        return client.getBytes("/api/v3/jobs/$jobId/stdout", params, headers, STDOUT_READ_BYTES).mapCatching {
            when {
                it.isSuccessful -> StepOutputChunk(it.body, it.header("X-Terminal") == "true", more = it.truncated)
                // Not written yet, or nothing past the offset.
                it.code == HTTP_NOT_FOUND || it.code == HTTP_RANGE_NOT_SATISFIABLE ->
                    StepOutputChunk(
                        ByteArray(0),
                        false,
                    )
                else -> error("Failed to read step output: HTTP ${it.code}")
            }
        }
    }

    /** A step's whole stderr; a step without any reads as empty. */
    suspend fun getStepStderr(
        client: CircleCIApiClient,
        jobId: String,
        execution: Int,
        stepNum: Int,
    ): Result<ByteArray> {
        return client.getBytes("/api/v3/jobs/$jobId/stderr", stepParams(execution, stepNum)).mapCatching {
            when {
                it.isSuccessful -> it.body
                it.code == HTTP_NOT_FOUND -> ByteArray(0)
                else -> error("Failed to read step errors: HTTP ${it.code}")
            }
        }
    }

    /**
     * A job's test results, via GET /api/v3/jobs/{id}/tests. The endpoint
     * streams JSON Lines and takes no filters, so this reads them all, a
     * line at a time as they arrive.
     */
    suspend fun getJobTests(
        client: CircleCIApiClient,
        jobId: String,
    ): Result<List<TestResultWire>> {
        return client.getStreaming("/api/v3/jobs/$jobId/tests") { code, body ->
            when (code) {
                in HTTP_SUCCESS -> parseTestResultLines(body)
                // A job that stored no test results has none to list.
                HTTP_NOT_FOUND -> emptyList()
                else -> error("Failed to read test results: HTTP $code")
            }
        }
    }

    private fun stepParams(
        execution: Int,
        stepNum: Int,
    ): Map<String, String> =
        mapOf(
            "filter[execution]" to execution.toString(),
            "filter[step_num]" to stepNum.toString(),
        )

    private companion object {
        val HTTP_SUCCESS = 200..299
        const val HTTP_NOT_FOUND = 404
        const val HTTP_RANGE_NOT_SATISFIABLE = 416

        // A long log reads in pieces this size, so none of it is held, or printed, all at once.
        const val STDOUT_READ_BYTES = 512 * 1024L
    }
}

private val lineGson = Gson()

/** Parse the JSON Lines body of GET /api/v3/jobs/{id}/tests, one result per line. */
internal fun parseTestResultLines(body: Reader): List<TestResultWire> =
    body.buffered().useLines { lines ->
        lines.filter { it.isNotBlank() }.map { lineGson.fromJson(it, TestResultWire::class.java) }.toList()
    }
