package com.circleci.idea.toolwindow.tree

import com.circleci.idea.icons.CircleCIIcons
import com.circleci.idea.run.elapsedSince
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.RowIcon
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.icons.RowIcon.Alignment
import com.intellij.util.ui.EmptyIcon
import java.time.Duration
import java.time.Instant
import javax.swing.Icon
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
        hasFocus: Boolean,
    ) {
        if (value !is CircleCITreeNode) {
            return
        }

        // Set icon based on node type and status
        icon =
            when (value) {
                is RootNode -> CircleCIIcons.PLUGIN_ICON
                is LoadingNode -> null
                is LoadMoreNode -> null
                is EmptyNode -> null
                is ErrorNode -> CircleCIIcons.Status.FAILED
                is RunNode -> runIcon(value)
                else -> value.getStatus()?.let { CircleCIIcons.getStatusIcon(it) }
            }
        toolTipText = (value as? RunNode)?.run?.triggeredBy?.let { "Triggered by $it" }

        // Set text and attributes
        val attributes =
            when (value) {
                is LoadingNode -> SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
                is LoadMoreNode -> SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES
                is EmptyNode -> SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
                is ErrorNode -> SimpleTextAttributes.ERROR_ATTRIBUTES
                else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
            }

        append(value.getDisplayText(), attributes)

        // Add additional info for certain node types
        when (value) {
            is RunNode -> {
                val run = value.run
                value.getRefText()?.let { appendDetail(it) }
                run.revision?.let { appendDetail(it.take(SHORT_REVISION_LENGTH)) }
                run.createdAt?.let { appendDetail(formatTimeAgo(it)) }
            }
            is WorkflowNode -> {
                value.workflow.createdAt?.let { appendDetail(formatTimeAgo(it)) }
            }
            is JobNode -> {
                val job = value.job
                if (job.type == "approval") {
                    appendDetail("(approval)")
                } else {
                    // Queued jobs have no start time yet; running ones count up to now.
                    elapsedSince(job.startedAt, job.endedAt)?.let { appendDetail(it) }
                }
            }
            else -> {}
        }
    }

    /** The run's status, then the avatar of whoever triggered it, when there is one. */
    private fun runIcon(node: RunNode): Icon {
        val status = CircleCIIcons.getStatusIcon(node.run.status)
        val avatar = RunAvatars.getInstance().iconFor(node.run) ?: return status
        // Centred: the ringed avatar is taller than the status icon, which
        // RowIcon would otherwise align to the top.
        return RowIcon(ICONS_IN_ROW, Alignment.CENTER).apply {
            setIcon(status, 0)
            setIcon(EmptyIcon.create(AVATAR_GAP), 1)
            setIcon(avatar, 2)
        }
    }

    private fun appendDetail(text: String) {
        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        append(text, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
    }

    /**
     * Format a timestamp as "X ago" (e.g., "2h ago", "5m ago").
     */
    private fun formatTimeAgo(time: Instant): String {
        val duration = Duration.between(time, Instant.now())
        return when {
            duration.toDays() > 0 -> "${duration.toDays()}d ago"
            duration.toHours() > 0 -> "${duration.toHours()}h ago"
            duration.toMinutes() > 0 -> "${duration.toMinutes()}m ago"
            else -> "just now"
        }
    }

    private companion object {
        const val SHORT_REVISION_LENGTH = 7
        const val AVATAR_GAP = 4
        const val ICONS_IN_ROW = 3
    }
}
