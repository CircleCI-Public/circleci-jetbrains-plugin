package com.circleci.idea.toolwindow.actions

import com.circleci.idea.ssh.CircleCISshService
import com.circleci.idea.state.JobDetailsState
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.awt.datatransfer.StringSelection

/**
 * Handler for copying the SSH command to clipboard.
 * Useful when the automatic terminal opening doesn't work.
 */
class CopySshHandler(project: Project) : JobActionHandler(project) {
    private val sshService = CircleCISshService.getInstance(project)

    override fun isAvailable(state: JobDetailsState): ActionAvailability {
        val jobDetails = state.jobDetails

        return if (jobDetails != null) {
            val sshCommand = sshService.buildSshCommand(jobDetails)
            if (sshCommand != null) {
                ActionAvailability.Available
            } else {
                ActionAvailability.Unavailable(
                    reason = "SSH is not enabled for this job",
                    title = "SSH Not Available",
                )
            }
        } else {
            ActionAvailability.Unavailable(
                reason = "Job details not loaded",
                title = "Cannot Copy SSH Command",
            )
        }
    }

    override suspend fun execute(state: JobDetailsState) {
        val jobDetails = state.jobDetails ?: return

        val sshCommand = sshService.buildSshCommand(jobDetails)
        if (sshCommand != null) {
            CopyPasteManager.getInstance().setContents(StringSelection(sshCommand))
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CircleCI Notifications")
                .createNotification(
                    "SSH Command Copied",
                    "SSH command copied to clipboard",
                    NotificationType.INFORMATION,
                )
                .notify(project)
        } else {
            Messages.showWarningDialog(
                project,
                "SSH is not enabled for this job",
                "SSH Not Available",
            )
        }
    }
}
