package com.circleci.idea.toolwindow.actions

import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.project.CircleCIProjectService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Action to refresh projects and pipelines.
 */
class RefreshAction :
    AnAction(
        "Refresh",
        "Refresh CircleCI projects and pipelines",
        AllIcons.Actions.Refresh,
    ),
    DumbAware {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectService = project.getService(CircleCIProjectService::class.java)
        val authService = CircleCIAuthService.getInstance(project)

        scope.launch {
            // Ensure authentication is restored
            authService.restoreAuthentication()

            // Always refresh projects (auth is checked inside)
            projectService.refresh()

            // Force tree reload after refresh
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                project.getService(com.circleci.idea.toolwindow.CircleCIToolWindowService::class.java)?.reloadTree()
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null
    }
}
