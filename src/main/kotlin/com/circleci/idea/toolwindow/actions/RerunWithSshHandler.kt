package com.circleci.idea.toolwindow.actions

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.ssh.CircleCISshService
import com.circleci.idea.ssh.SshValidationResult
import com.circleci.idea.state.JobDetailsState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handler for rerunning a job with SSH enabled.
 * This action reruns the entire workflow with SSH access enabled for the specific job.
 */
class RerunWithSshHandler(project: Project) : JobActionHandler(project) {
    private val apiService = CircleCIApiService.getInstance()
    private val sshService = CircleCISshService.getInstance(project)

    override fun isAvailable(state: JobDetailsState): ActionAvailability {
        val jobDetails = state.jobDetails
        val workflowId = jobDetails?.workflowId

        return when {
            jobDetails == null || jobDetails.id == null || workflowId == null -> {
                ActionAvailability.Unavailable(
                    reason =
                        "Cannot rerun job: workflow information not available.\n\n" +
                            "Try opening the job from the pipelines tree.",
                    title = "Cannot Rerun",
                )
            }
            else -> {
                // Check SSH support
                val validation = sshService.validateSshDetails(jobDetails)
                if (validation is SshValidationResult.NotSupported) {
                    ActionAvailability.Unavailable(
                        reason = "SSH is not available for GitHub App or GitLab projects",
                        title = "SSH Not Supported",
                    )
                } else {
                    ActionAvailability.Available
                }
            }
        }
    }

    override suspend fun execute(state: JobDetailsState) {
        val jobDetails = state.jobDetails ?: return
        val workflowId = jobDetails.workflowId ?: return
        val jobId = jobDetails.id ?: return

        // Confirm action
        val confirmed =
            withContext(Dispatchers.Main) {
                Messages.showYesNoDialog(
                    project,
                    "Rerun job '${jobDetails.name}' with SSH enabled?\n\n" +
                        "This will rerun the entire workflow with SSH access enabled for this specific job.",
                    "Confirm Rerun with SSH",
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
                    enableSsh = true,
                    jobs = listOf(jobId),
                )
            }

        // Show result
        withContext(Dispatchers.Main) {
            if (apiResult.isSuccess) {
                Messages.showInfoMessage(
                    project,
                    "Workflow rerun with SSH initiated successfully.\n\n" +
                        "Once the job starts running, use the 'Connect SSH' button to open a terminal session.",
                    "Rerun Successful",
                )
            } else {
                val error = apiResult.exceptionOrNull()?.message ?: "Unknown error"
                Messages.showErrorDialog(
                    project,
                    "Failed to rerun workflow with SSH: $error",
                    "Rerun Failed",
                )
            }
        }
    }
}
