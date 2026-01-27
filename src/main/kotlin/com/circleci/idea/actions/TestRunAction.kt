package com.circleci.idea.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

/**
 * Action to run a test with local configuration.
 */
class TestRunAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        Messages.showInfoMessage(
            "Test run functionality coming soon",
            "CircleCI Test Run",
        )
    }
}
