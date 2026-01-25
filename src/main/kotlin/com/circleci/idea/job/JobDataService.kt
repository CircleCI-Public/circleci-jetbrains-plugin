package com.circleci.idea.job

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.api.models.JobInfo
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.state.Job
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Service for managing job data.
 * Handles fetching and state management for jobs.
 */
@Service(Service.Level.PROJECT)
class JobDataService(private val project: Project) {

    private val logger = CircleCILogger.getInstance()
    private val stateStore = project.getService(CircleCIStateStore::class.java)
    private val apiService = CircleCIApiService.getInstance()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        logger.logLifecycleEvent("JobDataService initialized")
    }

    /**
     * Fetch jobs for a workflow.
     *
     * @param projectSlug Project slug
     * @param workflowId Workflow ID
     * @return List of jobs
     */
    suspend fun fetchJobs(
        projectSlug: String,
        workflowId: String
    ): List<Job> {
        logger.info("Fetching jobs for workflow $workflowId")
        _isLoading.value = true

        try {
            val result = apiService.getJobs(workflowId)

            return result.fold(
                onSuccess = { response ->
                    val jobs = response.items.map { convertToJob(it) }

                    // Update state store
                    stateStore.updateJobs(projectSlug, workflowId, jobs)

                    logger.info("Fetched ${jobs.size} jobs for workflow $workflowId")
                    jobs
                },
                onFailure = { error ->
                    logger.error("Failed to fetch jobs for workflow $workflowId: ${error.message}", error)
                    emptyList()
                }
            )

        } catch (e: Exception) {
            logger.error("Failed to fetch jobs for workflow $workflowId", e)
            return emptyList()
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Fetch jobs for multiple workflows.
     *
     * @param projectSlug Project slug
     * @param workflowIds List of workflow IDs
     * @return Map of workflow ID to jobs
     */
    suspend fun fetchJobsForWorkflows(
        projectSlug: String,
        workflowIds: List<String>
    ): Map<String, List<Job>> {
        logger.info("Fetching jobs for ${workflowIds.size} workflows")

        val results = mutableMapOf<String, List<Job>>()

        for (workflowId in workflowIds) {
            val jobs = fetchJobs(projectSlug, workflowId)
            results[workflowId] = jobs
        }

        return results
    }

    /**
     * Refresh jobs for a workflow.
     *
     * @param projectSlug Project slug
     * @param workflowId Workflow ID
     */
    suspend fun refreshJobs(projectSlug: String, workflowId: String) {
        logger.info("Refreshing jobs for workflow $workflowId")
        fetchJobs(projectSlug, workflowId)
    }

    /**
     * Convert API JobInfo to domain Job model.
     */
    private fun convertToJob(jobInfo: JobInfo): Job {
        return Job(
            id = jobInfo.id,
            jobNumber = jobInfo.jobNumber,
            name = jobInfo.name,
            status = jobInfo.status,
            type = jobInfo.type,
            startedAt = jobInfo.startedAt,
            stoppedAt = jobInfo.stoppedAt
        )
    }

    /**
     * Check if a job is running.
     */
    fun isRunning(job: Job): Boolean {
        return job.status in setOf("running", "queued")
    }

    /**
     * Check if a job is complete.
     */
    fun isComplete(job: Job): Boolean {
        return job.status in setOf("success", "failed", "canceled", "infrastructure_fail", "timedout")
    }

    /**
     * Check if a job failed.
     */
    fun isFailed(job: Job): Boolean {
        return job.status in setOf("failed", "infrastructure_fail", "timedout")
    }

    /**
     * Check if a job is an approval job.
     */
    fun isApprovalJob(job: Job): Boolean {
        return job.type == "approval"
    }

    /**
     * Check if a job is a build job.
     */
    fun isBuildJob(job: Job): Boolean {
        return job.type == "build"
    }

    /**
     * Check if a job needs approval.
     */
    fun needsApproval(job: Job): Boolean {
        return job.type == "approval" && job.status == "on_hold"
    }
}
