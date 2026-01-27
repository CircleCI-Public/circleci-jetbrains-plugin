package com.circleci.idea.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

/**
 * Action to validate CircleCI configuration.
 */
class ValidateConfigAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        Messages.showInfoMessage(
            "Config validation coming soon",
            "CircleCI Config Validation",
        )
    }
}
