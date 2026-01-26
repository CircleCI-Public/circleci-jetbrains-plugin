package com.circleci.idea.statusbar

import com.circleci.idea.icons.CircleCIIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * Presentation for the CircleCI status bar widget.
 * Shows current pipeline status with color coding and icon.
 */
class CircleCIStatusBarPresentation(private val project: Project) : StatusBarWidget.IconPresentation {

    private var isAuthenticated: Boolean = false
    private var isLoading: Boolean = false
    private var latestStatus: String? = null

    /**
     * Update authentication state.
     */
    fun updateAuthState(authenticated: Boolean) {
        isAuthenticated = authenticated
    }

    /**
     * Update pipeline status.
     */
    fun updatePipelineStatus(isLoading: Boolean, latestStatus: String?) {
        this.isLoading = isLoading
        this.latestStatus = latestStatus
    }

    override fun getTooltipText(): String {
        return when {
            !isAuthenticated -> "CircleCI: Not logged in. Click to open CircleCI panel and log in."
            isLoading -> "CircleCI: Loading pipelines..."
            latestStatus == null -> "CircleCI: No pipelines found. Click to open CircleCI panel."
            else -> "CircleCI: ${getStatusDisplayText(latestStatus!!)}. Click to open CircleCI panel."
        }
    }

    override fun getIcon(): Icon {
        return when {
            !isAuthenticated -> CircleCIIcons.PLUGIN_ICON
            isLoading -> CircleCIIcons.Status.RUNNING
            latestStatus == null -> CircleCIIcons.PLUGIN_ICON
            else -> getStatusIcon(latestStatus!!)
        }
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? {
        return Consumer { _: MouseEvent ->
            // Open CircleCI tool window on click
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("CircleCI")
            toolWindow?.show()
        }
    }

    /**
     * Get display text for status.
     */
    private fun getStatusDisplayText(status: String): String {
        return when (status.lowercase()) {
            "success" -> "✓ Success"
            "failed" -> "✗ Failed"
            "failing" -> "✗ Failing"
            "running" -> "Running"
            "canceled" -> "Canceled"
            "on_hold" -> "On Hold"
            "not_run" -> "Not Run"
            "error" -> "✗ Error"
            else -> status.replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Get status icon.
     */
    private fun getStatusIcon(status: String): Icon {
        return when (status.lowercase()) {
            "success" -> CircleCIIcons.Status.SUCCESS
            "failed", "failing", "error" -> CircleCIIcons.Status.FAILED
            "running", "queued" -> CircleCIIcons.Status.RUNNING
            "canceled" -> CircleCIIcons.Status.CANCELED
            "on_hold", "on-hold" -> CircleCIIcons.Status.ON_HOLD
            else -> CircleCIIcons.PLUGIN_ICON
        }
    }
}
