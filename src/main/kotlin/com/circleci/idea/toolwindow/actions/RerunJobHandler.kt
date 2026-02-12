package com.circleci.idea.toolwindow.actions

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.state.JobDetailsState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handler for rerunning a workflow from the start.
 * This action reruns the entire workflow that contains the selected job.
 */
class RerunJobHandler(project: Project) : JobActionHandler(project) {
    private val apiService = CircleCIApiService.getInstance()

    override fun isAvailable(state: JobDetailsState): ActionAvailability {
        val jobDetails = state.jobDetails
        val workflowId = jobDetails?.workflowId

        return if (jobDetails != null && workflowId != null) {
            ActionAvailability.Available
        } else {
            ActionAvailability.Unavailable(
                reason =
                    "Cannot rerun job: workflow information not available.\n\n" +
                        "Try opening the job from the pipelines tree.",
                title = "Cannot Rerun",
            )
        }
    }

    override suspend fun execute(state: JobDetailsState) {
        val jobDetails = state.jobDetails ?: return
        val workflowId = jobDetails.workflowId ?: return

        // Confirm action
        val confirmed =
            withContext(Dispatchers.Main) {
                Messages.showYesNoDialog(
                    project,
                    "Rerun workflow from start?\n\n" +
                        "This will rerun the entire workflow that contains job '${jobDetails.name}'.",
                    "Confirm Rerun",
                    Messages.getQuestionIcon(),
                ) == Messages.YES
            }

        if (!confirmed) return

        // Execute API call
        val apiResult =
            withContext(Dispatchers.IO) {
                apiService.rerunWorkflow(
                    workflowId = workflowId,
                    fromFailed = false,
                )
            }

        // Show result
        withContext(Dispatchers.Main) {
            if (apiResult.isSuccess) {
                Messages.showInfoMessage(
                    project,
                    "Workflow rerun initiated successfully",
                    "Rerun Successful",
                )
            } else {
                val error = apiResult.exceptionOrNull()?.message ?: "Unknown error"
                Messages.showErrorDialog(
                    project,
                    "Failed to rerun workflow: $error",
                    "Rerun Failed",
                )
            }
        }
    }
}
