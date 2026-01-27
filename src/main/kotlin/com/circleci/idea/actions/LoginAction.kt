package com.circleci.idea.actions

import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.auth.CircleCILoginDialog
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

/**
 * Action to log in to CircleCI.
 */
class LoginAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val authService = CircleCIAuthService.getInstance(project)

        // Check if already authenticated
        if (authService.isAuthenticated()) {
            val result =
                Messages.showYesNoDialog(
                    project,
                    "You are already logged in. Do you want to log out and log in with a different account?",
                    "Already Logged In",
                    Messages.getQuestionIcon(),
                )

            if (result == Messages.YES) {
                authService.logout()
                Messages.showInfoMessage(
                    project,
                    "You have been logged out. Please log in again.",
                    "Logged Out",
                )
            } else {
                return
            }
        }

        // Show login dialog
        val dialog = CircleCILoginDialog(project)
        dialog.show()
    }
}
