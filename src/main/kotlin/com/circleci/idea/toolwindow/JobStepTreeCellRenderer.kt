package com.circleci.idea.toolwindow

import com.circleci.idea.icons.CircleCIIcons
import com.intellij.ui.JBColor
import java.awt.Component
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer

/**
 * Custom cell renderer for job steps tree.
 */
class JobStepTreeCellRenderer : DefaultTreeCellRenderer() {
    override fun getTreeCellRendererComponent(
        tree: JTree?,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)

        if (value is DefaultMutableTreeNode) {
            val userObject = value.userObject

            when (userObject) {
                is StepNodeData -> {
                    text = "${userObject.stepNumber}. ${userObject.name ?: "Unknown Step"}"
                    icon = CircleCIIcons.Status.SUCCESS
                }
                is ActionNodeData -> {
                    val action = userObject.action
                    text = buildActionText(action)
                    icon = getActionIcon(action.status ?: "unknown")
                    foreground = getActionColor(action.status ?: "unknown")
                }
                else -> {
                    // Root node or other
                    text = userObject.toString()
                }
            }
        }

        return this
    }

    private fun buildActionText(action: com.circleci.idea.state.JobAction): String {
        val duration =
            if (action.runTimeMillis != null) {
                val seconds = action.runTimeMillis / 1000
                " (${seconds}s)"
            } else {
                ""
            }

        return "${action.name ?: "Unknown Action"}$duration"
    }

    private fun getActionIcon(status: String) =
        when (status.lowercase()) {
            "success" -> CircleCIIcons.Status.SUCCESS
            "failed" -> CircleCIIcons.Status.FAILED
            "running" -> CircleCIIcons.Status.RUNNING
            "canceled" -> CircleCIIcons.Status.CANCELED
            else -> null
        }

    private fun getActionColor(status: String) =
        when (status.lowercase()) {
            "success" -> JBColor.GREEN
            "failed" -> JBColor.RED
            "running" -> JBColor.BLUE
            "canceled" -> JBColor.GRAY
            else -> JBColor.BLACK
        }
}
