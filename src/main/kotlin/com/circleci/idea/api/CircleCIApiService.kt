package com.circleci.idea.api

import com.circleci.idea.api.clients.ConfigApiClient
import com.circleci.idea.api.clients.JobApiClient
import com.circleci.idea.api.clients.PipelineApiClient
import com.circleci.idea.api.clients.ProjectApiClient
import com.circleci.idea.api.clients.WorkflowApiClient
import com.circleci.idea.api.models.ArtifactsResponse
import com.circleci.idea.api.models.ConfigValidationResponse
import com.circleci.idea.api.models.JobDetailsInfo
import com.circleci.idea.api.models.JobInfo
import com.circleci.idea.api.models.PaginatedResponse
import com.circleci.idea.api.models.PipelineInfo
import com.circleci.idea.api.models.ProjectInfo
import com.circleci.idea.api.models.StepOutputResponse
import com.circleci.idea.api.models.TestResultsResponse
import com.circleci.idea.api.models.TriggerPipelineResponse
import com.circleci.idea.api.models.UserInfo
import com.circleci.idea.api.models.WorkflowInfo
import com.circleci.idea.logging.CircleCILogger
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

/**
 * Facade service for CircleCI API operations.
 * Delegates to specialized API clients for different domains (pipelines, workflows, jobs, etc.).
 *
 * This facade maintains backward compatibility with existing code while internally
 * organizing API calls into focused, testable client classes.
 */
@Service(Service.Level.APP)
class CircleCIApiService {
    private val logger = CircleCILogger.getInstance()
    private var client: CircleCIApiClient? = null

    // Specialized API clients
    private val pipelineClient = PipelineApiClient()
    private val workflowClient = WorkflowApiClient()
    private val jobClient = JobApiClient()
    private val projectClient = ProjectApiClient()
    private val configClient = ConfigApiClient()

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

    // ========== Pipeline Operations ==========

    /**
     * Get pipelines for a project.
     */
    fun getPipelines(
        projectSlug: String,
        branch: String? = null,
        pageToken: String? = null,
    ): Result<PaginatedResponse<PipelineInfo>> {
        return withClient { pipelineClient.getPipelines(it, projectSlug, branch, pageToken) }
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
        return withClient { pipelineClient.triggerPipeline(it, projectSlug, branch, configYaml, parameters) }
    }

    // ========== Workflow Operations ==========

    /**
     * Get workflows for a pipeline.
     */
    fun getWorkflows(pipelineId: String): Result<PaginatedResponse<WorkflowInfo>> {
        return withClient { workflowClient.getWorkflows(it, pipelineId) }
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
        return withClient { workflowClient.rerunWorkflow(it, workflowId, fromFailed, enableSsh, jobs) }
    }

    /**
     * Cancel a workflow.
     */
    fun cancelWorkflow(workflowId: String): Result<Unit> {
        return withClient { workflowClient.cancelWorkflow(it, workflowId) }
    }

    /**
     * Approve a workflow.
     */
    fun approveWorkflow(
        workflowId: String,
        approvalRequestId: String,
    ): Result<Unit> {
        return withClient { workflowClient.approveWorkflow(it, workflowId, approvalRequestId) }
    }

    /**
     * Rerun a job with SSH enabled.
     */
    fun rerunJobWithSsh(
        workflowId: String,
        jobId: String,
    ): Result<Unit> {
        return withClient { workflowClient.rerunJobWithSsh(it, workflowId, jobId) }
    }

    // ========== Job Operations ==========

    /**
     * Get jobs for a workflow.
     */
    fun getJobs(workflowId: String): Result<PaginatedResponse<JobInfo>> {
        return withClient { jobClient.getJobs(it, workflowId) }
    }

    /**
     * Get detailed job information.
     * Uses v1.1 API to get steps data since v2 doesn't include steps.
     */
    fun getJobDetails(
        projectSlug: String,
        jobNumber: Long,
    ): Result<JobDetailsInfo> {
        return withClient { jobClient.getJobDetails(it, projectSlug, jobNumber) }
    }

    /**
     * Cancel a job.
     */
    fun cancelJob(
        projectSlug: String,
        jobNumber: Long,
    ): Result<Unit> {
        return withClient { jobClient.cancelJob(it, projectSlug, jobNumber) }
    }

    /**
     * Get test results for a job.
     */
    fun getTestResults(
        projectSlug: String,
        jobNumber: Long,
    ): Result<TestResultsResponse> {
        return withClient { jobClient.getTestResults(it, projectSlug, jobNumber) }
    }

    /**
     * Fetch step output from output URL.
     * Note: This endpoint returns a JSON array directly, not wrapped in an object.
     */
    fun getStepOutput(outputUrl: String): Result<List<StepOutputResponse>> {
        return withClient { jobClient.getStepOutput(it, outputUrl) }
    }

    /**
     * Get artifacts for a job.
     */
    fun getArtifacts(
        projectSlug: String,
        jobNumber: Long,
    ): Result<ArtifactsResponse> {
        return withClient { jobClient.getArtifacts(it, projectSlug, jobNumber) }
    }

    // ========== Project Operations ==========

    /**
     * Get current user information.
     * Used for token validation and getting user ID.
     */
    fun getCurrentUser(): Result<UserInfo> {
        return withClient { projectClient.getCurrentUser(it) }
    }

    /**
     * Get followed projects.
     */
    fun getFollowedProjects(): Result<List<ProjectInfo>> {
        return withClient { projectClient.getFollowedProjects(it) }
    }

    // ========== Config Operations ==========

    /**
     * Validate configuration.
     */
    fun validateConfig(
        configYaml: String,
        projectSlug: String,
        branch: String,
    ): Result<ConfigValidationResponse> {
        return withClient { configClient.validateConfig(it, configYaml, projectSlug, branch) }
    }

    // ========== Helper Methods ==========

    /**
     * Execute an operation with the initialized client.
     * Ensures client is available before delegating to specialized clients.
     */
    private fun <T> withClient(operation: (CircleCIApiClient) -> Result<T>): Result<T> {
        val apiClient = client
        return if (apiClient != null) {
            operation(apiClient)
        } else {
            logger.error("API client not initialized")
            Result.failure(IllegalStateException("API client not initialized"))
        }
    }

    companion object {
        fun getInstance(): CircleCIApiService {
            return service()
        }
    }
}
