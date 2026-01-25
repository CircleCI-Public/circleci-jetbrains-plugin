package com.circleci.idea.actions

import com.circleci.idea.services.CircleCIProjectService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

/**
 * Action to refresh pipelines.
 */
class RefreshPipelinesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<CircleCIProjectService>().refreshPipelines()
    }
}
