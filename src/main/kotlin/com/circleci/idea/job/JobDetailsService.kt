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
     * @param workflowId Workflow ID (optional, needed for rerun actions)
     * @param jobName Job name (optional, used as fallback if API doesn't return it)
     * @param jobStatus Job status (optional, used as fallback if API doesn't return it)
     */
    suspend fun selectAndFetchJobDetails(
        jobId: String,
        jobNumber: Long?,
        projectSlug: String,
        workflowId: String? = null,
        jobName: String? = null,
        jobStatus: String? = null,
    ) {
        if (jobNumber == null) {
            logger.warn("Cannot fetch job details without job number")
            stateStore.setJobDetailsError("Job number not available")
            return
        }

        logger.info("Selecting job $jobId (number: $jobNumber) for project $projectSlug, workflow $workflowId")
        stateStore.selectJob(jobId, jobNumber, projectSlug, workflowId)

        try {
            fetchJobDetails(projectSlug, jobNumber, jobId, jobName, jobStatus, workflowId)
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
     * @param jobId Job ID (fallback if API doesn't return it)
     * @param jobName Job name (fallback if API doesn't return it)
     * @param jobStatus Job status (fallback if API doesn't return it)
     * @param workflowId Workflow ID
     */
    private suspend fun fetchJobDetails(
        projectSlug: String,
        jobNumber: Long,
        jobId: String? = null,
        jobName: String? = null,
        jobStatus: String? = null,
        workflowId: String? = null,
    ) {
        logger.info("Fetching job details for job $jobNumber (name: $jobName)")

        val result = apiService.getJobDetails(projectSlug, jobNumber)

        result.fold(
            onSuccess = { jobDetailsInfo ->
                logger.info("Raw API response for job $jobNumber: steps=${jobDetailsInfo.steps?.size ?: 0}")
                if (jobDetailsInfo.steps != null) {
                    jobDetailsInfo.steps.forEachIndexed { index, step ->
                        logger.info("  Step $index: name=${step.name}, actions=${step.actions?.size ?: 0}")
                    }
                } else {
                    logger.warn("No steps data in API response for job $jobNumber")
                }

                val jobDetails = convertToJobDetails(
                    jobDetailsInfo,
                    jobNumber,
                    workflowId,
                    jobId,
                    jobName,
                    jobStatus,
                )
                logger.info("Converted JobDetails: name=${jobDetails.name}, steps=${jobDetails.steps.size}, workflowId=$workflowId")
                jobDetails.steps.forEachIndexed { index, step ->
                    logger.info("  Converted Step $index: name=${step.name}, actions=${step.actions.size}")
                }

                stateStore.setJobDetails(jobDetails)
                logger.info("Successfully fetched job details for job $jobNumber")
            },
            onFailure = { error ->
                logger.error("Failed to fetch job details for job $jobNumber: ${error.message}", error)
                stateStore.setJobDetailsError(error.message ?: "Unknown error")
            },
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
    suspend fun rerunJobWithSsh(
        workflowId: String,
        jobId: String,
    ): Result<Unit> {
        logger.info("Rerunning job $jobId with SSH enabled")
        return apiService.rerunJobWithSsh(workflowId, jobId)
    }

    /**
     * Cancel job.
     */
    suspend fun cancelJob(
        projectSlug: String,
        jobNumber: Long,
    ): Result<Unit> {
        logger.info("Cancelling job $jobNumber")
        return apiService.cancelJob(projectSlug, jobNumber)
    }

    /**
     * Convert API JobDetailsInfo to domain JobDetails model.
     * Uses fallback values if API response doesn't include certain fields.
     */
    private fun convertToJobDetails(
        jobDetailsInfo: JobDetailsInfo,
        jobNumber: Long,
        workflowId: String? = null,
        fallbackJobId: String? = null,
        fallbackJobName: String? = null,
        fallbackJobStatus: String? = null,
    ): JobDetails {
        // Always calculate duration from timestamps (more reliable than API field)
        val duration = calculateDuration(
            jobDetailsInfo.startedAt,
            jobDetailsInfo.stoppedAt,
        ) ?: jobDetailsInfo.duration

        return JobDetails(
            id = jobDetailsInfo.id ?: fallbackJobId,
            jobNumber = jobDetailsInfo.jobNumber ?: jobNumber,
            name = jobDetailsInfo.name ?: fallbackJobName,
            projectSlug = jobDetailsInfo.projectSlug,
            workflowId = workflowId,
            status = jobDetailsInfo.status ?: fallbackJobStatus,
            type = jobDetailsInfo.type,
            startedAt = jobDetailsInfo.startedAt,
            stoppedAt = jobDetailsInfo.stoppedAt,
            duration = duration,
            resourceClass = jobDetailsInfo.executor?.resourceClass,
            parallelism = jobDetailsInfo.parallelism,
            steps = jobDetailsInfo.steps?.map { convertToJobStep(it) } ?: emptyList(),
            sshEnabled = jobDetailsInfo.ssh?.enabled ?: false,
            sshHost = jobDetailsInfo.ssh?.host,
            sshPort = jobDetailsInfo.ssh?.port,
            sshUser = jobDetailsInfo.ssh?.user,
            webUrl = jobDetailsInfo.webUrl,
        )
    }

    /**
     * Calculate duration from start and stop timestamps.
     * Returns null if either timestamp is missing or invalid.
     */
    private fun calculateDuration(
        startedAt: String?,
        stoppedAt: String?,
    ): Long? {
        if (startedAt == null || stoppedAt == null) {
            return null
        }

        return try {
            val startTime = java.time.Instant.parse(startedAt)
            val stopTime = java.time.Instant.parse(stoppedAt)
            val durationMillis = java.time.Duration.between(startTime, stopTime).toMillis()

            // Return null for negative durations (data inconsistency)
            if (durationMillis < 0) null else durationMillis
        } catch (e: Exception) {
            logger.debug("Failed to parse timestamps for duration calculation", e)
            null
        }
    }

    /**
     * Convert API JobStepInfo to domain JobStep model.
     */
    private fun convertToJobStep(stepInfo: JobStepInfo): JobStep {
        return JobStep(
            name = stepInfo.name,
            actions = stepInfo.actions?.map { convertToJobAction(it) } ?: emptyList(),
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
            index = actionInfo.index,
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

    /**
     * Fetch test results for a job.
     */
    suspend fun fetchTestResults(
        projectSlug: String,
        jobNumber: Long,
    ): Result<List<com.circleci.idea.state.TestResult>> {
        logger.info("Fetching test results for job $jobNumber")

        return apiService.getTestResults(projectSlug, jobNumber).map { response ->
            response.items?.map { testInfo ->
                com.circleci.idea.state.TestResult(
                    name = testInfo.name,
                    classname = testInfo.classname,
                    file = testInfo.file,
                    result = testInfo.result,
                    message = testInfo.message,
                    source = testInfo.source,
                    runTime = testInfo.runTime,
                    flaky = testInfo.flaky,
                )
            } ?: emptyList()
        }
    }

    /**
     * Fetch step output from output URL.
     */
    suspend fun fetchStepOutput(outputUrl: String): Result<List<com.circleci.idea.state.StepOutput>> {
        logger.info("Fetching step output from $outputUrl")

        return apiService.getStepOutput(outputUrl).map { outputList ->
            outputList.map { output ->
                com.circleci.idea.state.StepOutput(
                    message = output.message,
                    type = output.type,
                )
            }
        }
    }

    /**
     * Fetch artifacts for a job.
     */
    suspend fun fetchArtifacts(
        projectSlug: String,
        jobNumber: Long,
    ): Result<List<com.circleci.idea.state.Artifact>> {
        logger.info("Fetching artifacts for job $jobNumber")

        return apiService.getArtifacts(projectSlug, jobNumber).map { response ->
            response.items?.map { artifact ->
                com.circleci.idea.state.Artifact(
                    path = artifact.path,
                    nodeIndex = artifact.nodeIndex,
                    url = artifact.url,
                    prettyPath = artifact.prettyPath,
                )
            } ?: emptyList()
        }
    }
}
