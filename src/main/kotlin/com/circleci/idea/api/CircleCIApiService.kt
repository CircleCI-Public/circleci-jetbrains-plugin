package com.circleci.idea.api

import com.circleci.idea.api.clients.ConfigApiClient
import com.circleci.idea.api.clients.ContextApiClient
import com.circleci.idea.api.clients.JobApiClient
import com.circleci.idea.api.clients.ProjectApiClient
import com.circleci.idea.api.clients.RunApiClient
import com.circleci.idea.api.clients.SettingsApiClient
import com.circleci.idea.api.clients.StepOutputChunk
import com.circleci.idea.api.clients.V3Page
import com.circleci.idea.api.clients.WorkflowApiClient
import com.circleci.idea.api.models.ArtifactWire
import com.circleci.idea.api.models.ConfigValidationResult
import com.circleci.idea.api.models.Context
import com.circleci.idea.api.models.ContextDetail
import com.circleci.idea.api.models.ContextRestriction
import com.circleci.idea.api.models.EnvVar
import com.circleci.idea.api.models.JobDetailWire
import com.circleci.idea.api.models.JobWire
import com.circleci.idea.api.models.NamedEntity
import com.circleci.idea.api.models.ResourceUsageWire
import com.circleci.idea.api.models.RestrictionType
import com.circleci.idea.api.models.RunWire
import com.circleci.idea.api.models.TestResultWire
import com.circleci.idea.api.models.UserInfo
import com.circleci.idea.api.models.WorkflowWire
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.project.models.ProjectInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.nio.file.Path
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

    @Volatile
    private var client: CircleCIApiClient? = null

    // Specialized API clients
    private val runClient = RunApiClient()
    private val workflowClient = WorkflowApiClient()
    private val jobClient = JobApiClient()
    private val projectClient = ProjectApiClient()
    private val configClient = ConfigApiClient()
    private val settingsClient = SettingsApiClient()
    private val contextClient = ContextApiClient()

    /**
     * Initialize the API client with token, keeping the current one (and its
     * in-flight requests and rate limit) if the token and host are the same.
     */
    @Synchronized
    fun initialize(
        token: String,
        hostUrl: String = "https://circleci.com",
    ) {
        val current = client
        if (current != null && current.token == token && current.baseUrl == hostUrl) return
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
        return withClient { jobClient.getJob(it, jobId) }
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
        return withClient { jobClient.getStepStdout(it, jobId, execution, stepNum, offset) }
    }

    /**
     * Read a step's whole stderr.
     */
    fun getStepStderr(
        jobId: String,
        execution: Int,
        stepNum: Int,
    ): Result<ByteArray> {
        return withClient { jobClient.getStepStderr(it, jobId, execution, stepNum) }
    }

    /**
     * Get a job's test results.
     */
    fun getJobTests(jobId: String): Result<List<TestResultWire>> {
        return withClient { jobClient.getJobTests(it, jobId) }
    }

    /**
     * Look up a project, with its organization, by slug. Prefer
     * [com.circleci.idea.project.ProjectInfoService].
     */
    fun getProject(slug: String): Result<ProjectInfo> {
        return withClient { runClient.getProjectBySlug(it, slug) }
    }

    /**
     * Look up a project, with its organization, by ID; its slug is taken as a
     * standalone project's. Prefer [com.circleci.idea.project.ProjectInfoService].
     */
    fun getProjectById(projectId: String): Result<ProjectInfo> {
        return withClient { runClient.getProjectById(it, projectId) }
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
     * Get a job's resource usage, or null if it recorded none.
     */
    fun getJobResourceUsage(jobId: String): Result<ResourceUsageWire?> {
        return withClient { jobClient.getJobResourceUsage(it, jobId) }
    }

    /**
     * Get a job's artifacts.
     */
    fun getJobArtifacts(jobId: String): Result<List<ArtifactWire>> {
        return withClient { jobClient.getJobArtifacts(it, jobId) }
    }

    /**
     * Read up to [maxBytes] of an artifact.
     */
    fun readArtifact(
        url: String,
        maxBytes: Long,
    ): Result<RawResponse> {
        return withClient { jobClient.readArtifact(it, url, maxBytes) }
    }

    /**
     * Download an artifact to a file.
     */
    fun downloadArtifact(
        url: String,
        target: Path,
    ): Result<Long> {
        return withClient { it.download(url, target) }
    }

    // ========== Project Operations ==========

    /**
     * Get current user information.
     * Used for token validation and getting user ID.
     */
    fun getCurrentUser(): Result<UserInfo> {
        return withClient { projectClient.getCurrentUser(it) }
    }

    // ========== Settings Operations ==========

    /** A project's environment variables, with their values masked. */
    fun listProjectEnvVars(projectSlug: String): Result<List<EnvVar>> {
        return withClient { settingsClient.listProjectEnvVars(it, projectSlug) }
    }

    /** Add a project environment variable, or replace its value. */
    fun setProjectEnvVar(
        projectSlug: String,
        name: String,
        value: String,
    ): Result<Unit> {
        return withClient { settingsClient.setProjectEnvVar(it, projectSlug, name, value) }
    }

    fun deleteProjectEnvVar(
        projectSlug: String,
        name: String,
    ): Result<Unit> {
        return withClient { settingsClient.deleteProjectEnvVar(it, projectSlug, name) }
    }

    /** A page of an organization's contexts, from [cursor] (the first page at null). */
    fun listContexts(
        orgId: String,
        cursor: String?,
    ): Result<V3Page<Context>> {
        return withClient { settingsClient.listContexts(it, orgId, cursor) }
    }

    fun createContext(
        orgId: String,
        name: String,
    ): Result<Context> {
        return withClient { settingsClient.createContext(it, orgId, name) }
    }

    fun deleteContext(contextId: String): Result<Unit> {
        return withClient { settingsClient.deleteContext(it, contextId) }
    }

    /** A context, with the organization it's in. */
    fun getContext(contextId: String): Result<ContextDetail> {
        return withClient { contextClient.getContext(it, contextId) }
    }

    /** A context's environment variables, with their values masked. */
    fun listContextEnvVars(contextId: String): Result<List<EnvVar>> {
        return withClient { settingsClient.listContextEnvVars(it, contextId) }
    }

    /** Add a context environment variable, or replace its value. */
    fun setContextEnvVar(
        contextId: String,
        name: String,
        value: String,
    ): Result<Unit> {
        return withClient { settingsClient.setContextEnvVar(it, contextId, name, value) }
    }

    fun deleteContextEnvVar(
        contextId: String,
        name: String,
    ): Result<Unit> {
        return withClient { settingsClient.deleteContextEnvVar(it, contextId, name) }
    }

    fun listContextRestrictions(contextId: String): Result<List<ContextRestriction>> {
        return withClient { contextClient.listContextRestrictions(it, contextId) }
    }

    fun createContextRestriction(
        contextId: String,
        type: RestrictionType,
        value: String,
    ): Result<Unit> {
        return withClient { contextClient.createContextRestriction(it, contextId, type, value) }
    }

    fun deleteContextRestriction(
        contextId: String,
        restrictionId: String,
    ): Result<Unit> {
        return withClient { contextClient.deleteContextRestriction(it, contextId, restrictionId) }
    }

    /** An organization's groups, to restrict a context to. */
    fun listGroups(orgId: String): Result<List<NamedEntity>> {
        return withClient { contextClient.listGroups(it, orgId) }
    }

    /** A page of an organization's projects whose names contain [name], to restrict a context to. */
    fun searchProjects(
        orgId: String,
        name: String,
        cursor: String?,
    ): Result<V3Page<NamedEntity>> {
        return withClient { contextClient.searchProjects(it, orgId, name, cursor) }
    }

    // ========== Config Operations ==========

    /**
     * Validate configuration, resolving private orbs in the organization [orgId];
     * with none, only public orbs resolve.
     */
    fun validateConfig(
        configYaml: String,
        orgId: String?,
        branch: String,
    ): Result<ConfigValidationResult> {
        return withClient { configClient.validateConfig(it, configYaml, branch, orgId) }
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
