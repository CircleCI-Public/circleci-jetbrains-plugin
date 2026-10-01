package com.circleci.idea.actions

import com.circleci.idea.toolwindow.CircleCIToolWindowService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

/**
 * Action to refresh the run list.
 */
class RefreshRunsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<CircleCIToolWindowService>().refreshRuns()
    }
}
