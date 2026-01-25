package com.circleci.idea.toolwindow.tree

import com.circleci.idea.icons.CircleCIIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree

/**
 * Custom tree cell renderer for CircleCI tree nodes.
 * Displays status icons and formatted text for each node type.
 */
class CircleCITreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        if (value !is CircleCITreeNode) {
            return
        }

        // Set icon based on node type and status
        icon = when (value) {
            is RootNode -> CircleCIIcons.PLUGIN_ICON
            is ProjectNode -> CircleCIIcons.PLUGIN_ICON
            is LoadingNode -> null
            is LoadMoreNode -> null
            is EmptyNode -> null
            is ErrorNode -> CircleCIIcons.Status.FAILED
            else -> {
                val status = value.getStatus()
                if (status != null) {
                    CircleCIIcons.getStatusIcon(status)
                } else {
                    null
                }
            }
        }

        // Set text and attributes
        val attributes = when (value) {
            is LoadingNode -> SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
            is LoadMoreNode -> SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES
            is EmptyNode -> SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
            is ErrorNode -> SimpleTextAttributes.ERROR_ATTRIBUTES
            else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        }

        append(value.getDisplayText(), attributes)

        // Add additional info for certain node types
        when (value) {
            is PipelineNode -> {
                value.pipeline.vcs?.revision?.let { revision ->
                    val shortRevision = if (revision.length > 7) revision.substring(0, 7) else revision
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append(shortRevision, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
            is WorkflowNode -> {
                // Add created time if available
                value.workflow.createdAt?.let { createdAt ->
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append(formatTimeAgo(createdAt), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
            is JobNode -> {
                // Add job type if it's an approval job
                if (value.job.type == "approval") {
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("(approval)", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
            else -> {}
        }
    }

    /**
     * Format a timestamp as "X ago" (e.g., "2h ago", "5m ago").
     */
    private fun formatTimeAgo(timestamp: String): String {
        return try {
            val time = java.time.Instant.parse(timestamp)
            val now = java.time.Instant.now()
            val duration = java.time.Duration.between(time, now)

            when {
                duration.toDays() > 0 -> "${duration.toDays()}d ago"
                duration.toHours() > 0 -> "${duration.toHours()}h ago"
                duration.toMinutes() > 0 -> "${duration.toMinutes()}m ago"
                else -> "just now"
            }
        } catch (e: Exception) {
            ""
        }
    }
}
