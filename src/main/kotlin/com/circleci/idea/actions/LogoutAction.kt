package com.circleci.idea.actions

import com.circleci.idea.auth.CircleCIAuthService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

/**
 * Action to log out from CircleCI.
 */
class LogoutAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val authService = CircleCIAuthService.getInstance(project)

        if (!authService.isAuthenticated()) {
            Messages.showInfoMessage(
                project,
                "You are not logged in.",
                "Not Logged In",
            )
            return
        }

        val result =
            Messages.showYesNoDialog(
                project,
                "Are you sure you want to log out?",
                "Confirm Logout",
                Messages.getQuestionIcon(),
            )

        if (result == Messages.YES) {
            authService.logout()
            Messages.showInfoMessage(
                project,
                "You have been successfully logged out.",
                "Logged Out",
            )
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }

        val authService = CircleCIAuthService.getInstance(project)
        e.presentation.isEnabled = authService.isAuthenticated()
    }

    // Asking whether there's a login may read the password safe.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
