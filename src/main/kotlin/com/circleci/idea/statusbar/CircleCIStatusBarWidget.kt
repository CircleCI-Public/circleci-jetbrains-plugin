package com.circleci.idea.statusbar

import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.state.CircleCIStateStore
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Status bar widget for CircleCI.
 * Shows the current status of the latest pipeline/workflow.
 */
class CircleCIStatusBarWidget(private val project: Project) : StatusBarWidget {
    private val logger = CircleCILogger.getInstance()
    private val stateStore = CircleCIStateStore.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var statusBar: StatusBar? = null
    private val presentation = CircleCIStatusBarPresentation(project)

    init {
        logger.debug("CircleCIStatusBarWidget initialized")
        Disposer.register(project, this)
        observeState()
    }

    override fun ID(): String = "CircleCIStatusBar"

    override fun getPresentation(): StatusBarWidget.WidgetPresentation {
        return presentation
    }

    /**
     * Install the widget in the status bar.
     */
    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        logger.debug("Status bar widget installed")
    }

    /**
     * Observe state changes and update the widget.
     */
    private fun observeState() {
        // Observe auth state
        scope.launch {
            stateStore.auth.collectLatest { authState ->
                presentation.updateAuthState(authState.isAuthenticated)
                updateWidget()
            }
        }

        // Observe projects data to get latest pipeline status
        scope.launch {
            stateStore.projectsData.collectLatest { projectsDataState ->
                // Find the latest workflow status across all projects
                val latestStatus = findLatestWorkflowStatus(projectsDataState)
                presentation.updatePipelineStatus(
                    isLoading = projectsDataState.isRefreshing,
                    latestStatus = latestStatus,
                )
                updateWidget()
            }
        }
    }

    /**
     * Find the latest workflow status across all projects.
     */
    private fun findLatestWorkflowStatus(projectsDataState: com.circleci.idea.state.ProjectsDataState): String? {
        val allWorkflows =
            projectsDataState.data.values
                .flatMap { it.pipelines }
                .flatMap { it.workflows }
                .sortedByDescending { it.createdAt }

        return allWorkflows.firstOrNull()?.status
    }

    /**
     * Update the widget in the status bar.
     */
    private fun updateWidget() {
        statusBar?.updateWidget(ID())
    }

    override fun dispose() {
        logger.debug("CircleCIStatusBarWidget disposed")
        scope.cancel()
        statusBar?.removeWidget(ID())
    }
}
