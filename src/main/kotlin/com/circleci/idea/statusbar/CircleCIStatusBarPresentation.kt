package com.circleci.idea.statusbar

import com.circleci.idea.icons.BadgedIcon
import com.circleci.idea.icons.CircleCIIcons
import com.circleci.idea.icons.statusColor
import com.circleci.idea.run.RunStatus
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * Presentation for the CircleCI status bar widget.
 * Shows the latest run's status as a dot in its colour.
 */
class CircleCIStatusBarPresentation(private val project: Project) : StatusBarWidget.IconPresentation {
    private var isAuthenticated: Boolean = false
    private var isLoading: Boolean = false
    private var latestStatus: RunStatus? = null

    /**
     * Update authentication state.
     */
    fun updateAuthState(authenticated: Boolean) {
        isAuthenticated = authenticated
    }

    /**
     * Update run status.
     */
    fun updateRunStatus(
        isLoading: Boolean,
        latestStatus: RunStatus?,
    ) {
        this.isLoading = isLoading
        this.latestStatus = latestStatus
    }

    override fun getTooltipText(): String {
        return when {
            !isAuthenticated -> "CircleCI: Not logged in. Click to open CircleCI panel and log in."
            latestStatus == null && isLoading -> "CircleCI: Loading runs..."
            latestStatus == null -> "CircleCI: No runs found. Click to open CircleCI panel."
            else -> "CircleCI: Latest run ${latestStatus!!.label}. Click to open CircleCI panel."
        }
    }

    /**
     * CircleCI's icon, with a badge in the latest run's status colour, as the
     * IDE badges its own; kept while a refresh is under way.
     */
    override fun getIcon(): Icon {
        val status = latestStatus
        return if (isAuthenticated && status != null) {
            BadgedIcon(CircleCIIcons.PLUGIN_ICON, statusColor(status))
        } else {
            CircleCIIcons.PLUGIN_ICON
        }
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? {
        return Consumer { _: MouseEvent ->
            // Open CircleCI tool window on click
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("CircleCI")
            toolWindow?.show()
        }
    }
}
