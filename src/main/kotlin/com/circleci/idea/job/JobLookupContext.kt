package com.circleci.idea.job

import com.circleci.idea.api.models.JobInfo
import com.circleci.idea.state.JobDetailsState
import com.circleci.idea.toolwindow.tree.WorkflowNode

/**
 * Parameter object that encapsulates all context needed to look up and fetch job details.
 *
 * This consolidates the 6+ parameters previously passed to job detail fetching methods,
 * improving call-site readability and reducing LongParameterList violations.
 *
 * @property jobId Job ID (required)
 * @property jobNumber Job number (may be null, validated separately)
 * @property projectSlug Project slug (required)
 * @property workflowId Workflow ID (optional, needed for rerun actions)
 * @property fallbackJobName Job name used as fallback if API doesn't return it
 * @property fallbackJobStatus Job status used as fallback if API doesn't return it
 */
data class JobLookupContext(
    val jobId: String,
    val jobNumber: Long?,
    val projectSlug: String,
    val workflowId: String? = null,
    val fallbackJobName: String? = null,
    val fallbackJobStatus: String? = null,
) {
    /**
     * Validate that this context has the minimum required information.
     * Job number is required for API calls but may be null at construction time.
     */
    fun validate(): ValidationResult {
        return if (jobNumber == null) {
            ValidationResult.Failure("Job number not available")
        } else {
            ValidationResult.Success
        }
    }

    companion object {
        /**
         * Create a JobLookupContext from a JobNode and its parent WorkflowNode.
         * This is the primary factory method for tree selection events.
         */
        fun fromJobNode(
            job: JobInfo,
            workflowNode: WorkflowNode?,
        ): JobLookupContext {
            return JobLookupContext(
                jobId = job.id,
                jobNumber = job.jobNumber,
                projectSlug = job.projectSlug,
                workflowId = workflowNode?.workflow?.id,
                fallbackJobName = job.name,
                fallbackJobStatus = job.status,
            )
        }

        /**
         * Create a JobLookupContext from the current JobDetailsState.
         * This is used for refresh operations where we already have state.
         * Returns null if the state doesn't have enough information.
         */
        fun fromJobDetailsState(state: JobDetailsState): JobLookupContext? {
            val projectSlug = state.selectedProjectSlug ?: return null
            val jobNumber = state.selectedJobNumber ?: return null
            val jobId = state.selectedJobId ?: return null

            return JobLookupContext(
                jobId = jobId,
                jobNumber = jobNumber,
                projectSlug = projectSlug,
                workflowId = state.jobDetails?.workflowId,
                fallbackJobName = state.jobDetails?.name,
                fallbackJobStatus = state.jobDetails?.status,
            )
        }
    }
}

/**
 * Result of validation operations.
 */
sealed class ValidationResult {
    object Success : ValidationResult()

    data class Failure(val message: String) : ValidationResult()
}
