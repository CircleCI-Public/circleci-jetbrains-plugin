package com.circleci.idea.api

import com.circleci.idea.api.models.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

/**
 * Service layer for CircleCI API operations.
 * Provides typed methods for all API endpoints.
 */
@Service(Service.Level.APP)
class CircleCIApiService {
    private val gson = Gson()
    private var client: CircleCIApiClient? = null

    /**
     * Initialize the API client with token.
     */
    fun initialize(
        token: String,
        hostUrl: String = "https://circleci.com",
    ) {
        client = CircleCIApiClient(baseUrl = hostUrl, token = token)
    }

    /**
     * Check if client is initialized.
     */
    fun isInitialized(): Boolean = client != null

    /**
     * Get current user information.
     * Used for token validation and getting user ID.
     */
    fun getCurrentUser(): Result<UserInfo> {
        return executeRequest("/api/v2/me") { data ->
            gson.fromJson(data.toString(), UserInfo::class.java)
        }
    }

    /**
     * Get pipelines for a project.
     */
    fun getPipelines(
        projectSlug: String,
        branch: String? = null,
        pageToken: String? = null,
    ): Result<PaginatedResponse<PipelineInfo>> {
        val params = mutableMapOf<String, String>()
        if (branch != null) params["branch"] = branch
        if (pageToken != null) params["page-token"] = pageToken

        return executeRequest("/api/v2/project/$projectSlug/pipeline", params) { data ->
            gson.fromJson(data.toString(), object : TypeToken<PaginatedResponse<PipelineInfo>>() {}.type)
        }
    }

    /**
     * Get workflows for a pipeline.
     */
    fun getWorkflows(pipelineId: String): Result<PaginatedResponse<WorkflowInfo>> {
        return executeRequest("/api/v2/pipeline/$pipelineId/workflow") { data ->
            gson.fromJson(data.toString(), object : TypeToken<PaginatedResponse<WorkflowInfo>>() {}.type)
        }
    }

    /**
     * Get jobs for a workflow.
     */
    fun getJobs(workflowId: String): Result<PaginatedResponse<JobInfo>> {
        return executeRequest("/api/v2/workflow/$workflowId/job") { data ->
            gson.fromJson(data.toString(), object : TypeToken<PaginatedResponse<JobInfo>>() {}.type)
        }
    }

    /**
     * Rerun a workflow.
     */
    fun rerunWorkflow(
        workflowId: String,
        fromFailed: Boolean = false,
        enableSsh: Boolean = false,
        jobs: List<String>? = null,
    ): Result<Unit> {
        val body =
            buildMap {
                put("from_failed", fromFailed)
                put("enable_ssh", enableSsh)
                if (jobs != null) {
                    put("jobs", jobs)
                }
            }

        return executeRequest("/api/v2/workflow/$workflowId/rerun", emptyMap(), body) { Unit }
    }

    /**
     * Cancel a workflow.
     */
    fun cancelWorkflow(workflowId: String): Result<Unit> {
        return executePostRequest("/api/v2/workflow/$workflowId/cancel") { Unit }
    }

    /**
     * Approve a workflow.
     */
    fun approveWorkflow(
        workflowId: String,
        approvalRequestId: String,
    ): Result<Unit> {
        return executePostRequest("/api/v2/workflow/$workflowId/approve/$approvalRequestId") { Unit }
    }

    /**
     * Cancel a job.
     */
    fun cancelJob(
        projectSlug: String,
        jobNumber: Long,
    ): Result<Unit> {
        return executePostRequest("/api/v2/project/$projectSlug/job/$jobNumber/cancel") { Unit }
    }

    /**
     * Get detailed job information.
     * Uses v1.1 API to get steps data since v2 doesn't include steps.
     */
    fun getJobDetails(
        projectSlug: String,
        jobNumber: Long,
    ): Result<JobDetailsInfo> {
        val logger = com.circleci.idea.logging.CircleCILogger.getInstance()

        // Parse project slug: format is "vcs-slug/org/project" (e.g., "gh/username/repo")
        val parts = projectSlug.split("/")
        if (parts.size < 3) {
            return Result.failure(Exception("Invalid project slug format: $projectSlug"))
        }

        val vcsType = parts[0] // "gh", "bb", etc.
        val username = parts[1]
        val project = parts[2]

        // Use v1.1 API to get job details with steps
        return executeRequest("/api/v1.1/project/$vcsType/$username/$project/$jobNumber") { data ->
            val jobDetails = gson.fromJson(data.toString(), JobDetailsInfo::class.java)
            logger.debug("Fetched job details for job $jobNumber: ${jobDetails.steps?.size ?: 0} steps")
            jobDetails
        }
    }

    /**
     * Rerun a job with SSH enabled.
     */
    fun rerunJobWithSsh(
        workflowId: String,
        jobId: String,
    ): Result<Unit> {
        return executePostRequest("/api/v2/workflow/$workflowId/rerun") { Unit }
    }

    /**
     * Get test results for a job.
     */
    fun getTestResults(
        projectSlug: String,
        jobNumber: Long,
    ): Result<TestResultsResponse> {
        return executeRequest("/api/v2/project/$projectSlug/$jobNumber/tests") { data ->
            gson.fromJson(data.toString(), TestResultsResponse::class.java)
        }
    }

    /**
     * Fetch step output from output URL.
     * Note: This endpoint returns a JSON array directly, not wrapped in an object.
     * We use getRaw() to get the raw JSON string and parse it as an array.
     */
    fun getStepOutput(outputUrl: String): Result<List<StepOutputResponse>> {
        val apiClient = client ?: return Result.failure(IllegalStateException("API client not initialized"))

        // Extract the path from the full URL
        val path = outputUrl.substringAfter("circleci.com")

        return apiClient.getRaw(path).mapCatching { body ->
            // Parse as JSON array directly
            val jsonArray = gson.fromJson(body, com.google.gson.JsonArray::class.java)
            jsonArray.map {
                gson.fromJson(it, StepOutputResponse::class.java)
            }
        }
    }

    /**
     * Get artifacts for a job.
     */
    fun getArtifacts(
        projectSlug: String,
        jobNumber: Long,
    ): Result<ArtifactsResponse> {
        return executeRequest("/api/v2/project/$projectSlug/$jobNumber/artifacts") { data ->
            gson.fromJson(data.toString(), ArtifactsResponse::class.java)
        }
    }

    /**
     * Get followed projects.
     */
    fun getFollowedProjects(): Result<List<ProjectInfo>> {
        return executeRequest("/api/v1.1/projects") { data ->
            gson.fromJson(data.toString(), object : TypeToken<List<ProjectInfo>>() {}.type)
        }
    }

    /**
     * Validate configuration.
     */
    fun validateConfig(
        configYaml: String,
        projectSlug: String,
        branch: String,
    ): Result<ConfigValidationResponse> {
        val body =
            mapOf(
                "config_yaml" to configYaml,
                "pipeline_values" to
                    mapOf(
                        "branch" to branch,
                        "project_slug" to projectSlug,
                    ),
            )

        return executeRequest("/api/v2/compile-config-with-defaults", emptyMap(), body) { data ->
            gson.fromJson(data.toString(), ConfigValidationResponse::class.java)
        }
    }

    /**
     * Trigger a pipeline with custom config.
     */
    fun triggerPipeline(
        projectSlug: String,
        branch: String,
        configYaml: String? = null,
        parameters: Map<String, Any> = emptyMap(),
    ): Result<TriggerPipelineResponse> {
        val body =
            mutableMapOf<String, Any>(
                "branch" to branch,
                "parameters" to parameters,
            )
        if (configYaml != null) {
            body["config_yaml"] = configYaml
        }

        return executeRequest("/api/v2/project/$projectSlug/pipeline", emptyMap(), body) { data ->
            gson.fromJson(data.toString(), TriggerPipelineResponse::class.java)
        }
    }

    /**
     * Execute a GET request with typed response.
     */
    private fun <T> executeRequest(
        path: String,
        params: Map<String, String> = emptyMap(),
        body: Any? = null,
        parser: (com.google.gson.JsonObject) -> T,
    ): Result<T> {
        val apiClient = client ?: return Result.failure(IllegalStateException("API client not initialized"))

        val response =
            if (body != null) {
                apiClient.post(path, body)
            } else {
                apiClient.get(path, params)
            }

        return when (response) {
            is ApiResponse.Success -> {
                try {
                    Result.success(parser(response.data))
                } catch (e: Exception) {
                    Result.failure(Exception("Failed to parse response: ${e.message}"))
                }
            }
            is ApiResponse.Error -> Result.failure(Exception(response.message))
            is ApiResponse.Unauthorized -> Result.failure(Exception("Unauthorized: ${response.message}"))
            is ApiResponse.RateLimited -> Result.failure(Exception("Rate limited. Retry after ${response.retryAfter}s"))
        }
    }

    /**
     * Execute a POST request with no response body expected.
     */
    private fun executePostRequest(
        path: String,
        parser: (com.google.gson.JsonObject) -> Unit,
    ): Result<Unit> {
        val apiClient = client ?: return Result.failure(IllegalStateException("API client not initialized"))

        val response = apiClient.post(path)

        return when (response) {
            is ApiResponse.Success -> Result.success(Unit)
            is ApiResponse.Error -> Result.failure(Exception(response.message))
            is ApiResponse.Unauthorized -> Result.failure(Exception("Unauthorized: ${response.message}"))
            is ApiResponse.RateLimited -> Result.failure(Exception("Rate limited. Retry after ${response.retryAfter}s"))
        }
    }

    /**
     * Execute a POST request with body and parse response.
     */
    private fun <T> executePostRequestWithBody(
        path: String,
        body: Any,
        parser: (com.google.gson.JsonObject) -> T,
    ): Result<T> {
        val apiClient = client ?: return Result.failure(IllegalStateException("API client not initialized"))

        val response = apiClient.post(path, body)

        return when (response) {
            is ApiResponse.Success -> {
                try {
                    Result.success(parser(response.data))
                } catch (e: Exception) {
                    Result.failure(Exception("Failed to parse response: ${e.message}"))
                }
            }
            is ApiResponse.Error -> Result.failure(Exception(response.message))
            is ApiResponse.Unauthorized -> Result.failure(Exception("Unauthorized: ${response.message}"))
            is ApiResponse.RateLimited -> Result.failure(Exception("Rate limited. Retry after ${response.retryAfter}s"))
        }
    }

    /**
     * Validate CircleCI configuration.
     */
    fun validateConfig(configYaml: String): Result<ConfigValidationResponse> {
        val request = ConfigValidationRequest(config = configYaml)
        return executePostRequestWithBody("/api/v2/pipeline/config/compile-with-defaults", request) { data ->
            gson.fromJson(data.toString(), ConfigValidationResponse::class.java)
        }
    }

    companion object {
        fun getInstance(): CircleCIApiService {
            return service()
        }
    }
}
