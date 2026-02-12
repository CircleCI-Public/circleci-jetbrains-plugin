package com.circleci.idea.api.clients

import com.circleci.idea.api.CircleCIApiClient
import com.circleci.idea.api.models.ArtifactsResponse
import com.circleci.idea.api.models.JobDetailsInfo
import com.circleci.idea.api.models.JobInfo
import com.circleci.idea.api.models.PaginatedResponse
import com.circleci.idea.api.models.StepOutputResponse
import com.circleci.idea.api.models.TestResultsResponse
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken

/**
 * API client for job-related operations.
 * Handles fetching jobs, job details, test results, artifacts, and step output.
 */
class JobApiClient : CircleCIApiClientBase() {
    /**
     * Get jobs for a workflow.
     *
     * @param client The initialized API client
     * @param workflowId Workflow ID
     * @return Paginated list of jobs
     */
    fun getJobs(
        client: CircleCIApiClient,
        workflowId: String,
    ): Result<PaginatedResponse<JobInfo>> {
        return executeRequest(client, "/api/v2/workflow/$workflowId/job") { data ->
            gson.fromJson(data.toString(), object : TypeToken<PaginatedResponse<JobInfo>>() {}.type)
        }
    }

    /**
     * Get detailed job information.
     * Uses v1.1 API to get steps data since v2 doesn't include steps.
     *
     * @param client The initialized API client
     * @param projectSlug Project slug (e.g., "gh/username/repo")
     * @param jobNumber Job number
     * @return Job details including steps
     */
    fun getJobDetails(
        client: CircleCIApiClient,
        projectSlug: String,
        jobNumber: Long,
    ): Result<JobDetailsInfo> {
        // Parse project slug: format is "vcs-slug/org/project" (e.g., "gh/username/repo")
        val parts = projectSlug.split("/")
        if (parts.size < 3) {
            return Result.failure(Exception("Invalid project slug format: $projectSlug"))
        }

        val vcsType = parts[0] // "gh", "bb", etc.
        val username = parts[1]
        val project = parts[2]

        // Use v1.1 API to get job details with steps
        return executeRequest(client, "/api/v1.1/project/$vcsType/$username/$project/$jobNumber") { data ->
            val jobDetails = gson.fromJson(data.toString(), JobDetailsInfo::class.java)
            logger.debug("Fetched job details for job $jobNumber: ${jobDetails.steps?.size ?: 0} steps")
            jobDetails
        }
    }

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
     * Fetch step output from output URL.
     * Note: This endpoint returns a JSON array directly, not wrapped in an object.
     * However, it may also return a primitive (empty string, null) if there's no output.
     *
     * @param client The initialized API client
     * @param outputUrl Full output URL from CircleCI API
     * @return List of step output entries
     */
    fun getStepOutput(
        client: CircleCIApiClient,
        outputUrl: String,
    ): Result<List<StepOutputResponse>> {
        // Extract the path from the full URL
        val path = outputUrl.substringAfter("circleci.com")

        return client.getRaw(path).mapCatching { body ->
            // Handle empty or null responses
            if (body.isBlank() || body == "null") {
                return@mapCatching emptyList()
            }

            try {
                // Try to parse as JSON element first to check its type
                val jsonElement = gson.fromJson(body, JsonElement::class.java)

                when {
                    jsonElement.isJsonArray -> {
                        val jsonArray = jsonElement.asJsonArray
                        jsonArray.map {
                            gson.fromJson(it, StepOutputResponse::class.java)
                        }
                    }
                    jsonElement.isJsonPrimitive -> {
                        // If it's a primitive (like empty string), return empty list
                        emptyList()
                    }
                    else -> {
                        // Unknown format, return empty list
                        emptyList()
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to parse JSON element, returning empty list", e)
                emptyList()
            }
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
