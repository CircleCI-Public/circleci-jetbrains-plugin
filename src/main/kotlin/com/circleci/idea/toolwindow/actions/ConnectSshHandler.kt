package com.circleci.idea.toolwindow.actions

import com.circleci.idea.ssh.CircleCISshService
import com.circleci.idea.ssh.SshValidationResult
import com.circleci.idea.state.JobDetailsState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * Handler for connecting to a job via SSH.
 * Opens a terminal window with the SSH connection to the running job.
 */
class ConnectSshHandler(project: Project) : JobActionHandler(project) {
    private val sshService = CircleCISshService.getInstance(project)

    override fun isAvailable(state: JobDetailsState): ActionAvailability {
        val jobDetails = state.jobDetails

        return if (jobDetails != null) {
            // Validate SSH details
            when (sshService.validateSshDetails(jobDetails)) {
                is SshValidationResult.Valid -> ActionAvailability.Available
                is SshValidationResult.NotEnabled -> {
                    ActionAvailability.Unavailable(
                        reason = "SSH is not enabled for this job. Use 'Rerun with SSH' to enable it.",
                        title = "SSH Not Enabled",
                    )
                }
                is SshValidationResult.MissingHost -> {
                    ActionAvailability.Unavailable(
                        reason = "SSH host information is not available for this job",
                        title = "SSH Connection Error",
                    )
                }
                is SshValidationResult.NotSupported -> {
                    ActionAvailability.Unavailable(
                        reason = "SSH is not available for GitHub App or GitLab projects",
                        title = "SSH Not Supported",
                    )
                }
            }
        } else {
            ActionAvailability.Unavailable(
                reason = "Job details not loaded",
                title = "Cannot Connect",
            )
        }
    }

    override suspend fun execute(state: JobDetailsState) {
        val jobDetails = state.jobDetails ?: return

        val sshCommand = sshService.openSshSession(jobDetails)
        if (sshCommand != null) {
            Messages.showInfoMessage(
                project,
                "Opening terminal with SSH command:\n\n$sshCommand\n\n" +
                    "If the terminal doesn't open automatically, copy the SSH command using the " +
                    "'Copy SSH Command' button.",
                "SSH Connection",
            )
        } else {
            Messages.showErrorDialog(
                project,
                "Failed to build SSH command. Check that SSH is enabled for this job.",
                "SSH Connection Error",
            )
        }
    }
}
