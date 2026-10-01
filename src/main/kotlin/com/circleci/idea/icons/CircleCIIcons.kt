package com.circleci.idea.icons

import com.circleci.idea.run.RunStatus
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Icon loader for CircleCI plugin.
 * Provides access to all plugin icons.
 */
object CircleCIIcons {
    // Main plugin icon
    val PLUGIN_ICON: Icon = load("/icons/circleci.svg")

    // Status icons
    object Status {
        val SUCCESS: Icon = load("/icons/status-success.svg")
        val FAILED: Icon = load("/icons/status-failed.svg")
        val RUNNING: Icon = load("/icons/status-running.svg")
        val CANCELED: Icon = load("/icons/status-canceled.svg")
        val ON_HOLD: Icon = load("/icons/status-on-hold.svg")
    }

    // Action icons
    object Actions {
        val RERUN: Icon = load("/icons/action-rerun.svg")
        val CANCEL: Icon = load("/icons/action-cancel.svg")
        val APPROVE: Icon = load("/icons/action-approve.svg")
        val SSH: Icon = load("/icons/action-ssh.svg")
    }

    /**
     * Get status icon by status string.
     */
    fun getStatusIcon(status: String): Icon {
        return when (status.lowercase()) {
            "success" -> Status.SUCCESS
            "failed", "failing", "error" -> Status.FAILED
            "running", "queued" -> Status.RUNNING
            "canceled" -> Status.CANCELED
            "on_hold", "on-hold" -> Status.ON_HOLD
            else -> PLUGIN_ICON
        }
    }

    /**
     * Get status icon for a run, workflow or job status.
     */
    fun getStatusIcon(status: RunStatus): Icon {
        return when (status) {
            RunStatus.SUCCESS -> Status.SUCCESS
            RunStatus.FAILED, RunStatus.FAILING, RunStatus.ERROR -> Status.FAILED
            RunStatus.CREATED, RunStatus.QUEUED, RunStatus.RUNNING -> Status.RUNNING
            RunStatus.CANCELED, RunStatus.CANCELING, RunStatus.NOT_RUN, RunStatus.UNAUTHORIZED -> Status.CANCELED
            RunStatus.ON_HOLD -> Status.ON_HOLD
            RunStatus.UNKNOWN -> PLUGIN_ICON
        }
    }

    private fun load(path: String): Icon {
        return IconLoader.getIcon(path, CircleCIIcons::class.java)
    }
}
