package com.circleci.idea.api.clients

import com.circleci.idea.api.CircleCIApiClient
import com.circleci.idea.api.models.ArtifactsResponse
import com.circleci.idea.api.models.JobDetailWire
import com.circleci.idea.api.models.TestResultWire
import com.circleci.idea.api.models.V3Entity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** A read of a step's output: the new bytes, and whether the output has finished. */
class StepOutputChunk(val data: ByteArray, val terminal: Boolean)

/**
 * API client for job-related operations.
 * Handles job details, step output, tests and artifacts, and cancelling jobs.
 */
class JobApiClient : CircleCIApiClientBase() {
    /**
     * Cancel a job.
     *
     * @param client The initialized API client
     * @param projectSlug Project slug
     * @param jobNumber Job number
     * @return Success or error
     */
    fun cancelJob(
        client: CircleCIApiClient,
        projectSlug: String,
        jobNumber: Long,
    ): Result<Unit> {
        return executePostRequest(client, "/api/v2/project/$projectSlug/job/$jobNumber/cancel")
    }

    /**
     * Get artifacts for a job.
     *
     * @param client The initialized API client
     * @param projectSlug Project slug
     * @param jobNumber Job number
     * @return Artifacts response
     */
    fun getArtifacts(
        client: CircleCIApiClient,
        projectSlug: String,
        jobNumber: Long,
    ): Result<ArtifactsResponse> {
        return executeRequest(client, "/api/v2/project/$projectSlug/$jobNumber/artifacts") { data ->
            gson.fromJson(data.toString(), ArtifactsResponse::class.java)
        }
    }

    /** A job with its steps, via GET /api/v3/jobs/{id}. */
    fun getJob(
        client: CircleCIApiClient,
        jobId: String,
    ): Result<JobDetailWire> {
        return executeRequest(client, "/api/v3/jobs/$jobId") { data ->
            gson.fromJson<V3Entity<JobDetailWire>>(data, object : TypeToken<V3Entity<JobDetailWire>>() {}.type).data
                ?: error("No job found for $jobId")
        }
    }

    /**
     * A step's stdout from byte [offset] on, and whether it has finished
     * (the X-Terminal header). A step with no output yet reads as empty.
     */
    fun getStepStdout(
        client: CircleCIApiClient,
        jobId: String,
        execution: Int,
        stepNum: Int,
        offset: Long,
    ): Result<StepOutputChunk> {
        val headers = mapOf("Range" to "bytes=$offset-")
        return client.getBytes("/api/v3/jobs/$jobId/stdout", stepParams(execution, stepNum), headers).mapCatching {
            when {
                it.isSuccessful -> StepOutputChunk(it.body, it.headers["X-Terminal"] == "true")
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
    fun getStepStderr(
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
     * streams JSON Lines and takes no filters, so this reads them all.
     */
    fun getJobTests(
        client: CircleCIApiClient,
        jobId: String,
    ): Result<List<TestResultWire>> {
        return client.getBytes("/api/v3/jobs/$jobId/tests").mapCatching { response ->
            when {
                response.isSuccessful -> parseTestResultLines(String(response.body, Charsets.UTF_8))
                // A job that stored no test results has none to list.
                response.code == HTTP_NOT_FOUND -> emptyList()
                else -> error("Failed to read test results: HTTP ${response.code}")
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
        const val HTTP_NOT_FOUND = 404
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}

private val lineGson = Gson()

/** Parse the JSON Lines body of GET /api/v3/jobs/{id}/tests, one result per line. */
internal fun parseTestResultLines(body: String): List<TestResultWire> {
    return body.lineSequence().filter { it.isNotBlank() }.map {
        lineGson.fromJson(
            it,
            TestResultWire::class.java,
        )
    }.toList()
}
