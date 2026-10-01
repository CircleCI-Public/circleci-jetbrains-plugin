package com.circleci.idea.api

import com.circleci.idea.api.clients.ConfigApiClient
import com.circleci.idea.api.clients.JobApiClient
import com.circleci.idea.api.clients.ProjectApiClient
import com.circleci.idea.api.clients.RunApiClient
import com.circleci.idea.api.clients.StepOutputChunk
import com.circleci.idea.api.clients.V3Page
import com.circleci.idea.api.clients.WorkflowApiClient
import com.circleci.idea.api.models.ArtifactsResponse
import com.circleci.idea.api.models.ConfigValidationResponse
import com.circleci.idea.api.models.JobDetailWire
import com.circleci.idea.api.models.JobWire
import com.circleci.idea.api.models.ProjectInfo
import com.circleci.idea.api.models.RunWire
import com.circleci.idea.api.models.TestResultsResponse
import com.circleci.idea.api.models.UserInfo
import com.circleci.idea.api.models.WorkflowWire
import com.circleci.idea.logging.CircleCILogger
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.time.Instant

/**
 * Facade service for CircleCI API operations.
 * Delegates to specialized API clients for different domains (runs, workflows, jobs, etc.).
 *
 * This facade maintains backward compatibility with existing code while internally
 * organizing API calls into focused, testable client classes.
 */
@Service(Service.Level.APP)
class CircleCIApiService {
    private val logger = CircleCILogger.getInstance()
    private var client: CircleCIApiClient? = null

    // Specialized API clients
    private val runClient = RunApiClient()
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

    // ========== Run Operations ==========

    /**
     * Search a project's runs, newest first.
     */
    @Suppress("LongParameterList")
    fun searchRuns(
        projectId: String,
        from: Instant,
        to: Instant,
        filter: String,
        limit: Int,
        cursor: String? = null,
    ): Result<V3Page<RunWire>> {
        return withClient { runClient.searchRuns(it, projectId, from, to, filter, limit, cursor) }
    }

    /**
     * List the authenticated user's runs across all projects, newest first.
     */
    @Suppress("LongParameterList")
    fun listMyRuns(
        phase: String?,
        currentOutcome: String?,
        from: Instant?,
        to: Instant?,
        limit: Int,
        cursor: String? = null,
    ): Result<V3Page<RunWire>> {
        return withClient { runClient.listMyRuns(it, phase, currentOutcome, from, to, limit, cursor) }
    }

    /**
     * Get the workflows of a run.
     */
    fun getRunWorkflows(runId: String): Result<List<WorkflowWire>> {
        return withClient { runClient.getRunWorkflows(it, runId) }
    }

    /**
     * Get the jobs of a workflow.
     */
    fun getWorkflowJobs(workflowId: String): Result<List<JobWire>> {
        return withClient { runClient.getWorkflowJobs(it, workflowId) }
    }

    /**
     * Get a job with its steps.
     */
    fun getJob(jobId: String): Result<JobDetailWire> {
        return withClient { runClient.getJob(it, jobId) }
    }

    /**
     * Read a step's stdout from a byte offset.
     */
    fun getStepStdout(
        jobId: String,
        execution: Int,
        stepNum: Int,
        offset: Long,
    ): Result<StepOutputChunk> {
        return withClient { runClient.getStepStdout(it, jobId, execution, stepNum, offset) }
    }

    /**
     * Read a step's whole stderr.
     */
    fun getStepStderr(
        jobId: String,
        execution: Int,
        stepNum: Int,
    ): Result<ByteArray> {
        return withClient { runClient.getStepStderr(it, jobId, execution, stepNum) }
    }

    /**
     * Get a project's ID from its slug.
     */
    fun getProjectId(slug: String): Result<String> {
        return withClient { client ->
            runClient.getProjectBySlug(client, slug).mapCatching { it.id ?: error("Project $slug has no ID") }
        }
    }

    /**
     * Get the ID of the organization a project belongs to.
     */
    fun getProjectOrgId(projectId: String): Result<String> {
        return withClient { client ->
            runClient.getProjectById(client, projectId).mapCatching {
                it.references?.org?.id ?: error("Project $projectId has no organization")
            }
        }
    }

    // ========== Workflow Operations ==========

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
