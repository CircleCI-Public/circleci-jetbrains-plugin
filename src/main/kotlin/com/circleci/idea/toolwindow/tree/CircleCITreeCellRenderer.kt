package com.circleci.idea.toolwindow.tree

import com.circleci.idea.icons.CircleCIIcons
import com.circleci.idea.run.elapsedSince
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.render.RenderingUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.time.Duration
import java.time.Instant
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.plaf.basic.BasicTreeUI
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer

/**
 * Renders the run tree. Runs take two lines, as the Pull Requests list's
 * rows do: the run's status and title, its details in grey underneath, and
 * the avatar of whoever triggered it in a column on the right. Everything
 * else (workflows, jobs, messages) takes one line.
 */
class CircleCITreeCellRenderer : TreeCellRenderer {
    private val lineRenderer = LineRenderer()
    private val runRenderer = RunRowRenderer()

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        if (value is RunNode) {
            return runRenderer.render(tree, value, selected, hasFocus)
        }
        return lineRenderer.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
    }

    /** Workflows, jobs and messages: an icon, the text, and any detail in grey after it. */
    private class LineRenderer : ColoredTreeCellRenderer() {
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

            icon = iconFor(value)
            append(value.getDisplayText(), attributesFor(value))
            detailFor(value)?.let { appendDetail(it) }
        }

        private fun iconFor(node: CircleCITreeNode): Icon? =
            when (node) {
                is RootNode -> CircleCIIcons.PLUGIN_ICON
                is LoadingNode, is LoadMoreNode, is EmptyNode -> null
                is ErrorNode -> CircleCIIcons.Status.FAILED
                else -> node.getStatus()?.let { CircleCIIcons.getStatusIcon(it) }
            }

        private fun attributesFor(node: CircleCITreeNode): SimpleTextAttributes =
            when (node) {
                is LoadingNode, is EmptyNode -> SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
                is LoadMoreNode -> SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES
                is ErrorNode -> SimpleTextAttributes.ERROR_ATTRIBUTES
                else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
            }

        private fun detailFor(node: CircleCITreeNode): String? =
            when (node) {
                is WorkflowNode -> node.workflow.createdAt?.let { formatTimeAgo(it) }
                // Queued jobs have no start time yet; running ones count up to now.
                is JobNode ->
                    if (node.job.type == "approval") {
                        "(approval)"
                    } else {
                        elapsedSince(
                            node.job.startedAt,
                            node.job.endedAt,
                        )
                    }
                else -> null
            }

        private fun appendDetail(text: String) {
            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append(text, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    /**
     * A run: status icon and title, then its ref, revision and age in grey
     * underneath, lined up with the title; the avatar on the right, centred
     * across both lines.
     *
     * The row spans the tree's visible width, so the avatars form a column
     * at its right edge. A tree sizes rows to their renderer, so this works
     * out the room to the right of the row's indent itself.
     */
    private class RunRowRenderer {
        private val title = SimpleColoredComponent()
        private val details = SimpleColoredComponent()
        private val avatar = JBLabel()
        private val lines =
            JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(title)
                add(details)
            }
        private val row = RowPanel()

        init {
            for (line in listOf(title, details)) {
                line.isOpaque = false
                line.ipad = JBUI.emptyInsets()
                line.alignmentX = Component.LEFT_ALIGNMENT
            }
            row.add(lines, BorderLayout.CENTER)
            row.add(avatar, BorderLayout.EAST)
        }

        fun render(
            tree: JTree,
            node: RunNode,
            selected: Boolean,
            hasFocus: Boolean,
        ): Component {
            val run = node.run
            val foreground = RenderingUtil.getForeground(tree, selected)
            // Grey, unless on the focused selection's background, where it wouldn't read.
            val detailForeground = if (selected && hasFocus) foreground else UIUtil.getContextHelpForeground()

            title.clear()
            title.icon = CircleCIIcons.getStatusIcon(run.status)
            title.iconTextGap = JBUI.scale(ICON_GAP)
            title.append(node.getDisplayText(), SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, foreground))

            details.clear()
            // Indent the details to line up with the title's text, past its icon.
            details.ipad = JBUI.insetsLeft(title.icon.iconWidth + title.iconTextGap)
            val parts =
                listOfNotNull(
                    node.getRefText(),
                    run.revision?.take(SHORT_REVISION_LENGTH),
                    run.createdAt?.let(::formatTimeAgo),
                )
            details.append(
                parts.joinToString(" · "),
                SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, detailForeground),
            )

            avatar.icon = RunAvatars.getInstance().iconFor(run)
            avatar.isVisible = avatar.icon != null
            row.toolTipText = run.triggeredBy?.let { "Triggered by $it" }
            row.rowWidth = roomRightOfIndent(tree, node)
            return row
        }

        /**
         * The tree's visible width less the row's indent, as BasicTreeUI
         * places rows: a child indent per level, less one for a hidden root,
         * plus one when root handles show.
         */
        private fun roomRightOfIndent(
            tree: JTree,
            node: DefaultMutableTreeNode,
        ): Int? {
            val ui = tree.ui as? BasicTreeUI
            val visible = (tree.parent as? JViewport)?.extentSize?.width ?: 0
            if (ui == null || visible <= 0) return null
            val depthOffset = (if (tree.isRootVisible) 0 else -1) + (if (tree.showsRootHandles) 1 else 0)
            val indent = tree.insets.left + (ui.leftChildIndent + ui.rightChildIndent) * (node.level + depthOffset)
            return (visible - indent - JBUI.scale(RIGHT_MARGIN)).takeIf { it > 0 }
        }
    }

    /** A run's row, as wide as it's told it may be (so its avatar sits at the right edge). */
    private class RowPanel : JBPanel<RowPanel>(BorderLayout(JBUI.scale(GAP), 0)) {
        var rowWidth: Int? = null

        init {
            isOpaque = false
            border = JBUI.Borders.empty(VERTICAL_PADDING, 0)
        }

        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            return rowWidth?.let { Dimension(maxOf(it, size.width), size.height) } ?: size
        }
    }

    private companion object {
        const val SHORT_REVISION_LENGTH = 7
        const val GAP = 8
        const val ICON_GAP = 4
        const val VERTICAL_PADDING = 3
        const val RIGHT_MARGIN = 8

        /**
         * Format a timestamp as "X ago" (e.g., "2h ago", "5m ago").
         */
        fun formatTimeAgo(time: Instant): String {
            val duration = Duration.between(time, Instant.now())
            return when {
                duration.toDays() > 0 -> "${duration.toDays()}d ago"
                duration.toHours() > 0 -> "${duration.toHours()}h ago"
                duration.toMinutes() > 0 -> "${duration.toMinutes()}m ago"
                else -> "just now"
            }
        }
    }
}
