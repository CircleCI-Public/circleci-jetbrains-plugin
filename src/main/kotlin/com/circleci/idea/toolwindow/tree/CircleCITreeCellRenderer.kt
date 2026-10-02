package com.circleci.idea.toolwindow.tree

import com.circleci.idea.icons.CircleCIIcons
import com.circleci.idea.icons.StatusDotIcon
import com.circleci.idea.run.RunStatus
import com.circleci.idea.run.elapsedSince
import com.intellij.ui.ColoredTreeCellRenderer
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
 * Renders the run tree, marking statuses with small coloured dots. Runs take two lines, as the Pull Requests list's
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

        // Statuses are the same small dots as on runs, throughout the tree.
        private fun iconFor(node: CircleCITreeNode): Icon? =
            when (node) {
                is RootNode -> CircleCIIcons.PLUGIN_ICON
                is LoadingNode, is LoadMoreNode, is EmptyNode -> null
                is ErrorNode -> StatusDotIcon.of(RunStatus.FAILED)
                else -> node.getStatus()?.let { StatusDotIcon.of(it) }
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
     * A run, in columns: its status (a coloured dot) and title, with its revision, age
     * and author in grey underneath, from the row's left edge (the icon is part of
     * the first line); then its branch; then the avatar of whoever triggered
     * it, centred across both lines.
     *
     * The row spans the tree's visible width, so the branch and avatar
     * columns line up at its right edge, and shrinks with it: text that no
     * longer fits ends in "…". A tree sizes rows to their renderer, so this
     * works out the room to the right of the row's indent itself.
     */
    private class RunRowRenderer {
        private val title = JBLabel()
        private val details = JBLabel().apply { font = JBUI.Fonts.smallFont() }
        private val branch = JBLabel().apply { font = JBUI.Fonts.smallFont() }
        private val avatar = JBLabel().apply { border = JBUI.Borders.empty(0, AVATAR_PADDING) }
        private val lines =
            JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(title)
                add(details)
            }
        private val columns =
            JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(GAP), 0)).apply {
                isOpaque = false
                add(branch, BorderLayout.CENTER)
                add(avatar, BorderLayout.EAST)
            }
        private val row = RowPanel()

        init {
            title.alignmentX = Component.LEFT_ALIGNMENT
            details.alignmentX = Component.LEFT_ALIGNMENT
            // The text gives way first when the row narrows: let it shrink to nothing.
            lines.minimumSize = Dimension(0, 0)
            row.add(lines, BorderLayout.CENTER)
            row.add(columns, BorderLayout.EAST)
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

            title.icon = StatusDotIcon.of(run.status)
            title.iconTextGap = JBUI.scale(ICON_GAP)
            title.text = node.getDisplayText()
            title.foreground = foreground

            details.text =
                listOfNotNull(
                    run.revision?.take(SHORT_REVISION_LENGTH),
                    run.createdAt?.let(::formatTimeAgo),
                    // Who triggered it, or failing that, whose commit it ran.
                    run.triggeredBy ?: run.commitAuthor,
                ).joinToString(" · ")
            details.foreground = detailForeground

            val width = roomRightOfIndent(tree, node)
            branch.text = node.getRefText().orEmpty()
            branch.foreground = detailForeground
            branch.preferredSize = Dimension(branchColumnWidth(node, width), branch.preferredSize.height)

            avatar.icon = RunAvatars.getInstance().iconFor(run)
            avatar.isVisible = avatar.icon != null
            row.toolTipText = run.triggeredBy?.let { "Triggered by $it" }
            row.rowWidth = width
            return row
        }

        /**
         * One width for the branch column across the list, so it lines up:
         * the widest of the runs' refs, but at most a share of the row, past
         * which a long branch name ends in "…".
         */
        private fun branchColumnWidth(
            node: RunNode,
            rowWidth: Int?,
        ): Int {
            val metrics = branch.getFontMetrics(branch.font)
            val siblings =
                node.parent?.let {
                        parent ->
                    (0 until parent.childCount).map { parent.getChildAt(it) }
                } ?: listOf(node)
            val widest =
                siblings.filterIsInstance<RunNode>().maxOfOrNull {
                    metrics.stringWidth(
                        it.getRefText().orEmpty(),
                    )
                } ?: 0
            val cap = rowWidth?.let { (it * BRANCH_COLUMN_SHARE).toInt() } ?: widest
            return minOf(widest, cap)
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

    /**
     * A run's row, exactly as wide as it's told it may be: the room right
     * of its indent, however much or little its text would like.
     */
    private class RowPanel : JBPanel<RowPanel>(BorderLayout(JBUI.scale(GAP), 0)) {
        var rowWidth: Int? = null

        init {
            isOpaque = false
            border = JBUI.Borders.empty(VERTICAL_PADDING, 0)
        }

        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            return rowWidth?.let { Dimension(it, size.height) } ?: size
        }
    }

    private companion object {
        const val SHORT_REVISION_LENGTH = 7
        const val GAP = 8
        const val ICON_GAP = 4
        const val VERTICAL_PADDING = 3
        const val RIGHT_MARGIN = 8
        const val AVATAR_PADDING = 6
        const val BRANCH_COLUMN_SHARE = 0.3

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
