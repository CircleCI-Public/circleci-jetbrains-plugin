package com.circleci.idea.api

import com.circleci.idea.api.models.*
import com.circleci.idea.settings.CircleCISettings
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
    fun initialize(token: String, hostUrl: String = "https://circleci.com") {
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
        pageToken: String? = null
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
        enableSsh: Boolean = false
    ): Result<Unit> {
        val body = mapOf(
            "from_failed" to fromFailed,
            "enable_ssh" to enableSsh
        )

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
    fun approveWorkflow(workflowId: String, approvalRequestId: String): Result<Unit> {
        return executePostRequest("/api/v2/workflow/$workflowId/approve/$approvalRequestId") { Unit }
    }

    /**
     * Cancel a job.
     */
    fun cancelJob(projectSlug: String, jobNumber: Long): Result<Unit> {
        return executePostRequest("/api/v2/project/$projectSlug/job/$jobNumber/cancel") { Unit }
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
    fun validateConfig(configYaml: String, projectSlug: String, branch: String): Result<ConfigValidationResponse> {
        val body = mapOf(
            "config_yaml" to configYaml,
            "pipeline_values" to mapOf(
                "branch" to branch,
                "project_slug" to projectSlug
            )
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
        parameters: Map<String, Any> = emptyMap()
    ): Result<TriggerPipelineResponse> {
        val body = mutableMapOf<String, Any>(
            "branch" to branch,
            "parameters" to parameters
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
        parser: (com.google.gson.JsonObject) -> T
    ): Result<T> {
        val apiClient = client ?: return Result.failure(IllegalStateException("API client not initialized"))

        val response = if (body != null) {
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
    private fun executePostRequest(path: String, parser: (com.google.gson.JsonObject) -> Unit): Result<Unit> {
        val apiClient = client ?: return Result.failure(IllegalStateException("API client not initialized"))

        val response = apiClient.post(path)

        return when (response) {
            is ApiResponse.Success -> Result.success(Unit)
            is ApiResponse.Error -> Result.failure(Exception(response.message))
            is ApiResponse.Unauthorized -> Result.failure(Exception("Unauthorized: ${response.message}"))
            is ApiResponse.RateLimited -> Result.failure(Exception("Rate limited. Retry after ${response.retryAfter}s"))
        }
    }

    companion object {
        fun getInstance(): CircleCIApiService {
            return service()
        }
    }
}
