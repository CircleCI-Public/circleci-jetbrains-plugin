package com.circleci.idea.icons

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

    private fun load(path: String): Icon {
        return IconLoader.getIcon(path, CircleCIIcons::class.java)
    }
}
