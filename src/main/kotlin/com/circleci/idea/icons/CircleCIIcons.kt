package com.circleci.idea.icons

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Icon loader for CircleCI plugin. Statuses are drawn as dots, by
 * [statusIcon] and [StatusDot], rather than loaded.
 */
object CircleCIIcons {
    // Main plugin icon
    val PLUGIN_ICON: Icon = load("/icons/circleci.svg")

    // Action icons
    object Actions {
        val RERUN: Icon = load("/icons/action-rerun.svg")
        val CANCEL: Icon = load("/icons/action-cancel.svg")
        val APPROVE: Icon = load("/icons/action-approve.svg")
        val SSH: Icon = load("/icons/action-ssh.svg")
    }

    private fun load(path: String): Icon {
        return IconLoader.getIcon(path, CircleCIIcons::class.java)
    }
}
