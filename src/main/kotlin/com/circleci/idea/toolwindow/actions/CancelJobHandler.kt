package com.circleci.idea.toolwindow.actions

import com.circleci.idea.job.JobDetailsService
import com.circleci.idea.state.JobDetailsState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handler for cancelling a running job.
 */
class CancelJobHandler(project: Project) : JobActionHandler(project) {
    private val jobDetailsService = project.getService(JobDetailsService::class.java)

    override fun isAvailable(state: JobDetailsState): ActionAvailability {
        // Use fallback values from state if jobDetails hasn't loaded yet
        val jobNumber = state.jobDetails?.jobNumber ?: state.selectedJobNumber
        val projectSlug = state.jobDetails?.projectSlug ?: state.selectedProjectSlug

        return if (jobNumber != null && projectSlug != null) {
            ActionAvailability.Available
        } else {
            ActionAvailability.Unavailable(
                reason = "Cannot cancel job: job information not available",
                title = "Cannot Cancel",
            )
        }
    }

    override suspend fun execute(state: JobDetailsState) {
        // Use fallback values from state if jobDetails hasn't loaded yet
        val jobNumber = state.jobDetails?.jobNumber ?: state.selectedJobNumber ?: return
        val projectSlug = state.jobDetails?.projectSlug ?: state.selectedProjectSlug ?: return
        val jobName = state.jobDetails?.name ?: "this job"

        // Confirm action
        val confirmed =
            withContext(Dispatchers.Main) {
                Messages.showYesNoDialog(
                    project,
                    "Cancel job '$jobName'?",
                    "Confirm Cancel",
                    Messages.getQuestionIcon(),
                ) == Messages.YES
            }

        if (!confirmed) return

        // Execute API call
        val apiResult =
            withContext(Dispatchers.IO) {
                jobDetailsService.cancelJob(projectSlug, jobNumber)
            }

        // Show result and refresh
        withContext(Dispatchers.Main) {
            if (apiResult.isSuccess) {
                Messages.showInfoMessage(
                    project,
                    "Job cancelled successfully",
                    "Cancel Successful",
                )
                jobDetailsService.refreshJobDetails()
            } else {
                val error = apiResult.exceptionOrNull()?.message ?: "Unknown error"
                Messages.showErrorDialog(
                    project,
                    "Failed to cancel job: $error",
                    "Cancel Failed",
                )
            }
        }
    }
}
