package com.circleci.idea.actions

import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.auth.CircleCILoginDialog
import com.circleci.idea.auth.CircleCIOAuthLoginDialog
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory

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

        // Log in the browser or with a token, as the GitHub plugin offers
        val choices =
            DefaultActionGroup(
                DumbAwareAction.create("Log In via CircleCI...") { CircleCIOAuthLoginDialog(project).show() },
                DumbAwareAction.create("Log In with Token...") { CircleCILoginDialog(project).show() },
            )
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                "Log In to CircleCI",
                choices,
                e.dataContext,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false,
            )
            .showCenteredInCurrentWindow(project)
    }
}
