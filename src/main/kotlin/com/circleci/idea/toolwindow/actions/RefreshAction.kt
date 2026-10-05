package com.circleci.idea.toolwindow.actions

import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.project.CircleCIProjectService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Action to refresh projects and runs.
 */
class RefreshAction(private val scope: CoroutineScope) :
    AnAction(
        "Refresh",
        "Refresh CircleCI projects and runs",
        AllIcons.Actions.Refresh,
    ),
    DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectService = project.getService(CircleCIProjectService::class.java)
        val authService = CircleCIAuthService.getInstance(project)

        scope.launch {
            withContext(Dispatchers.IO) { authService.restoreAuthentication() }

            // Always refresh projects (auth is checked inside)
            projectService.refresh()

            // Re-fetch the runs in place, keeping the tree as it is. A
            // change to the project list rebuilds it on its own.
            project.getService(com.circleci.idea.toolwindow.CircleCIToolWindowService::class.java)?.refreshRuns()
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null
    }
}
