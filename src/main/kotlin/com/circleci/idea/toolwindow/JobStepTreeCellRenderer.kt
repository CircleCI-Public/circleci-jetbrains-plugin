package com.circleci.idea.toolwindow

import com.circleci.idea.icons.CircleCIIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Custom cell renderer for job steps tree.
 * Uses ColoredTreeCellRenderer for proper styling consistent with pipeline tree.
 */
class JobStepTreeCellRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        if (value !is DefaultMutableTreeNode) {
            return
        }

        val userObject = value.userObject

        when (userObject) {
            is StepNodeData -> {
                append("${userObject.stepNumber}. ${userObject.name ?: "Unknown Step"}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                icon = CircleCIIcons.Status.SUCCESS
            }
            is ActionNodeData -> {
                val action = userObject.action
                val duration =
                    if (action.runTimeMillis != null) {
                        val seconds = action.runTimeMillis / 1000
                        " (${seconds}s)"
                    } else {
                        ""
                    }

                // Main text
                append(action.name ?: "Unknown Action", SimpleTextAttributes.REGULAR_ATTRIBUTES)

                // Duration in grayed text
                if (duration.isNotEmpty()) {
                    append(duration, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }

                // Set icon based on status
                icon = getActionIcon(action.status ?: "unknown")
            }
            else -> {
                append(userObject.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
    }

    private fun getActionIcon(status: String) =
        when (status.lowercase()) {
            "success" -> CircleCIIcons.Status.SUCCESS
            "failed" -> CircleCIIcons.Status.FAILED
            "running" -> CircleCIIcons.Status.RUNNING
            "canceled" -> CircleCIIcons.Status.CANCELED
            else -> null
        }
}
