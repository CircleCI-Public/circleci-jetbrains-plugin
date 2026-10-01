package com.circleci.idea.api.clients

import com.circleci.idea.api.CircleCIApiClient
import com.circleci.idea.api.models.ArtifactsResponse
import com.circleci.idea.api.models.TestResultsResponse

/**
 * API client for job-related operations.
 * Handles cancelling jobs and fetching test results and artifacts.
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
     * Get test results for a job.
     *
     * @param client The initialized API client
     * @param projectSlug Project slug
     * @param jobNumber Job number
     * @return Test results
     */
    fun getTestResults(
        client: CircleCIApiClient,
        projectSlug: String,
        jobNumber: Long,
    ): Result<TestResultsResponse> {
        return executeRequest(client, "/api/v2/project/$projectSlug/$jobNumber/tests") { data ->
            gson.fromJson(data.toString(), TestResultsResponse::class.java)
        }
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
}
