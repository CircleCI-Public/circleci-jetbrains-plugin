package com.circleci.idea.job

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.api.models.JobActionInfo
import com.circleci.idea.api.models.JobDetailsInfo
import com.circleci.idea.api.models.JobStepInfo
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.state.JobAction
import com.circleci.idea.state.JobDetails
import com.circleci.idea.state.JobStep
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Service for managing job details.
 * Handles fetching detailed job information including steps and output.
 */
@Service(Service.Level.PROJECT)
class JobDetailsService(private val project: Project) {

    private val logger = CircleCILogger.getInstance()
    private val stateStore = CircleCIStateStore.getInstance(project)
    private val apiService = CircleCIApiService.getInstance()

    init {
        logger.logLifecycleEvent("JobDetailsService initialized")
    }

    /**
     * Select a job and fetch its details.
     *
     * @param jobId Job ID
     * @param jobNumber Job number
     * @param projectSlug Project slug
     */
    suspend fun selectAndFetchJobDetails(
        jobId: String,
        jobNumber: Long?,
        projectSlug: String
    ) {
        if (jobNumber == null) {
            logger.warn("Cannot fetch job details without job number")
            stateStore.setJobDetailsError("Job number not available")
            return
        }

        logger.info("Selecting job $jobId (number: $jobNumber) for project $projectSlug")
        stateStore.selectJob(jobId, jobNumber, projectSlug)

        try {
            fetchJobDetails(projectSlug, jobNumber)
        } catch (e: Exception) {
            logger.error("Failed to fetch job details for job $jobNumber", e)
            stateStore.setJobDetailsError("Failed to load job details: ${e.message}")
        }
    }

    /**
     * Fetch detailed job information.
     *
     * @param projectSlug Project slug
     * @param jobNumber Job number
     */
    private suspend fun fetchJobDetails(
        projectSlug: String,
        jobNumber: Long
    ) {
        logger.info("Fetching job details for job $jobNumber")

        val result = apiService.getJobDetails(projectSlug, jobNumber)

        result.fold(
            onSuccess = { jobDetailsInfo ->
                val jobDetails = convertToJobDetails(jobDetailsInfo)
                stateStore.setJobDetails(jobDetails)
                logger.info("Successfully fetched job details for job $jobNumber")
            },
            onFailure = { error ->
                logger.error("Failed to fetch job details for job $jobNumber: ${error.message}", error)
                stateStore.setJobDetailsError(error.message ?: "Unknown error")
            }
        )
    }

    /**
     * Refresh job details.
     */
    suspend fun refreshJobDetails() {
        val currentState = stateStore.jobDetails.value
        val projectSlug = currentState.selectedProjectSlug
        val jobNumber = currentState.selectedJobNumber

        if (projectSlug != null && jobNumber != null) {
            logger.info("Refreshing job details for job $jobNumber")
            fetchJobDetails(projectSlug, jobNumber)
        }
    }

    /**
     * Clear selected job.
     */
    fun clearJobDetails() {
        logger.info("Clearing job details")
        stateStore.clearJobDetails()
    }

    /**
     * Rerun job with SSH enabled.
     */
    suspend fun rerunJobWithSsh(workflowId: String, jobId: String): Result<Unit> {
        logger.info("Rerunning job $jobId with SSH enabled")
        return apiService.rerunJobWithSsh(workflowId, jobId)
    }

    /**
     * Cancel job.
     */
    suspend fun cancelJob(projectSlug: String, jobNumber: Long): Result<Unit> {
        logger.info("Cancelling job $jobNumber")
        return apiService.cancelJob(projectSlug, jobNumber)
    }

    /**
     * Convert API JobDetailsInfo to domain JobDetails model.
     */
    private fun convertToJobDetails(jobDetailsInfo: JobDetailsInfo): JobDetails {
        return JobDetails(
            id = jobDetailsInfo.id,
            jobNumber = jobDetailsInfo.jobNumber,
            name = jobDetailsInfo.name,
            projectSlug = jobDetailsInfo.projectSlug,
            status = jobDetailsInfo.status,
            type = jobDetailsInfo.type,
            startedAt = jobDetailsInfo.startedAt,
            stoppedAt = jobDetailsInfo.stoppedAt,
            duration = jobDetailsInfo.duration,
            resourceClass = jobDetailsInfo.executor?.resourceClass,
            parallelism = jobDetailsInfo.parallelism,
            steps = jobDetailsInfo.steps?.map { convertToJobStep(it) } ?: emptyList(),
            sshEnabled = jobDetailsInfo.ssh?.enabled ?: false,
            sshHost = jobDetailsInfo.ssh?.host,
            sshPort = jobDetailsInfo.ssh?.port,
            sshUser = jobDetailsInfo.ssh?.user,
            webUrl = jobDetailsInfo.webUrl
        )
    }

    /**
     * Convert API JobStepInfo to domain JobStep model.
     */
    private fun convertToJobStep(stepInfo: JobStepInfo): JobStep {
        return JobStep(
            name = stepInfo.name,
            actions = stepInfo.actions?.map { convertToJobAction(it) } ?: emptyList()
        )
    }

    /**
     * Convert API JobActionInfo to domain JobAction model.
     */
    private fun convertToJobAction(actionInfo: JobActionInfo): JobAction {
        return JobAction(
            name = actionInfo.name,
            status = actionInfo.status,
            startTime = actionInfo.startTime,
            endTime = actionInfo.endTime,
            runTimeMillis = actionInfo.runTimeMillis,
            outputUrl = actionInfo.outputUrl,
            step = actionInfo.step,
            index = actionInfo.index
        )
    }

    /**
     * Get SSH command for a job (if SSH is enabled).
     */
    fun getSshCommand(jobDetails: JobDetails): String? {
        if (!jobDetails.sshEnabled || jobDetails.sshHost == null) {
            return null
        }

        val user = jobDetails.sshUser ?: "circleci"
        val port = jobDetails.sshPort ?: 22
        val host = jobDetails.sshHost

        return "ssh -p $port $user@$host"
    }

    /**
     * Format duration in milliseconds to human-readable string.
     */
    fun formatDuration(durationMillis: Long?): String {
        if (durationMillis == null) return "N/A"

        val seconds = durationMillis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60)
            minutes > 0 -> String.format("%dm %ds", minutes, seconds % 60)
            else -> String.format("%ds", seconds)
        }
    }
}
