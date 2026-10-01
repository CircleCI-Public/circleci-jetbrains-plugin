package com.circleci.idea.toolwindow.tree

import com.circleci.idea.run.RunStatus
import com.circleci.idea.state.Run
import com.circleci.idea.state.Workflow
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import java.awt.Container
import java.time.Instant
import javax.swing.JLabel
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Lays out the run tree as the tool window does and measures its rows:
 * runs take two lines, and span the visible width so their avatars line up
 * at its right edge.
 */
class RunTreeRenderingTest : BasePlatformTestCase() {
    private fun run(id: String) =
        Run(
            id = id,
            number = 3102,
            projectId = null,
            projectSlug = "gh/org/repo",
            repositoryName = "org/repo",
            status = RunStatus.SUCCESS,
            createdAt = Instant.now(),
            branch = "main",
            tag = null,
            revision = "7d3b7bcb6cc2",
            commitSubject = "feat: a change",
            commitAuthor = "someone",
            triggeredBy = "someone",
        )

    private fun workflow(run: Run) =
        Workflow(
            id = "w",
            name = "build",
            status = RunStatus.SUCCESS,
            createdAt = Instant.now(),
            endedAt = null,
            runId = run.id,
            runNumber = run.number,
            projectSlug = run.projectSlug,
        )

    private fun layOut(width: Int): Pair<Tree, RootNode> {
        val root = RootNode()
        val first = RunNode(run("a")).also { it.add(WorkflowNode(workflow(it.run))) }
        root.add(first)
        root.add(RunNode(run("b")))
        val tree =
            Tree(DefaultTreeModel(root)).apply {
                isRootVisible = false
                showsRootHandles = true
                cellRenderer = CircleCITreeCellRenderer()
                rowHeight = 0
            }
        tree.expandPath(TreePath(arrayOf(root, first)))
        val scrollPane = JBScrollPane(tree).apply { setSize(width, HEIGHT) }
        layoutAll(scrollPane)
        TreeUtil.invalidateCacheAndRepaint(tree.ui)
        layoutAll(scrollPane)
        return tree to root
    }

    private fun layoutAll(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach { layoutAll(it) }
    }

    fun testRunsAreTwoLinesAndSpanTheVisibleWidth() {
        val (tree, _) = layOut(WIDTH)
        val runRow = tree.getRowBounds(0)
        val workflowRow = tree.getRowBounds(1)
        val viewportWidth = (tree.parent as javax.swing.JViewport).extentSize.width

        assertTrue(
            "a run is taller than a one-line row (${runRow.height} vs ${workflowRow.height})",
            runRow.height > workflowRow.height,
        )
        assertTrue(
            "a run reaches near the right edge (${runRow.x + runRow.width} of $viewportWidth)",
            runRow.x + runRow.width in (viewportWidth - EDGE_SLACK)..viewportWidth,
        )
        assertEquals(
            "every run row ends at the same x (an aligned avatar column)",
            runRow.x + runRow.width,
            tree.getRowBounds(
                2,
            ).let {
                it.x + it.width
            },
        )
    }

    fun testNarrowerWindowNarrowsTheRuns() {
        val wide = layOut(WIDTH).first.getRowBounds(0)
        val narrow = layOut(WIDTH - NARROWER_BY).first.getRowBounds(0)
        assertEquals("the run row follows the visible width", wide.width - NARROWER_BY, narrow.width)
    }

    fun testTitleIsNotCappedInLength() {
        val subject = "feat: " + "a long commit subject ".repeat(6).trim()
        val node = RunNode(run("a").copy(commitSubject = subject))
        assertEquals("the whole subject, for the label to fit to the room", "#3102 $subject", node.getDisplayText())
    }

    fun testRowShrinksBelowItsText() {
        val wide = layOut(WIDTH).first.getRowBounds(0)
        val tight = layOut(TIGHT_WIDTH).first.getRowBounds(0)
        assertEquals(
            "a run row narrows with the window, past its text's width",
            wide.width - (WIDTH - TIGHT_WIDTH),
            tight.width,
        )
    }

    /** A run row's renderer, laid out at its row's bounds. */
    private fun renderedRun(
        tree: Tree,
        root: RootNode,
        index: Int,
    ): Container {
        val row = tree.getRowForPath(TreePath(arrayOf(root, root.getChildAt(index))))
        val component =
            tree.cellRenderer.getTreeCellRendererComponent(
                tree,
                root.getChildAt(index),
                false,
                false,
                false,
                row,
                false,
            ) as Container
        component.setBounds(tree.getRowBounds(row))
        layoutAll(component)
        return component
    }

    /** The labels within a component, with their x relative to it. */
    private fun labels(component: Container): List<Pair<JLabel, Int>> {
        fun collect(
            container: Container,
            offset: Int,
        ): List<Pair<JLabel, Int>> =
            container.components.flatMap { child ->
                when (child) {
                    is JLabel -> listOf(child to offset + child.x)
                    is Container -> collect(child, offset + child.x)
                    else -> emptyList()
                }
            }
        return collect(component, 0)
    }

    fun testColumnsAndLines() {
        val (tree, root) = layOut(WIDTH)
        val first = labels(renderedRun(tree, root, 0))
        val second = labels(renderedRun(tree, root, 1))
        val component = renderedRun(tree, root, 0)

        val (title, titleX) = first.first { it.first.text.startsWith("#3102") }
        val (_, detailsX) = first.first { it.first.text.contains("7d3b7bc") }
        assertEquals("the details start under the status icon, at the title row's left edge", titleX, detailsX)
        assertNotNull("the title row carries the status icon", title.icon)
        val details = first.first { it.first.text.contains("7d3b7bc") }.first.text
        assertTrue("the details end with who triggered the run ($details)", details.endsWith(" · someone"))

        val branchX = first.first { it.first.text == "main" }.second
        assertEquals("the branch column lines up across runs", branchX, second.first { it.first.text == "main" }.second)
        assertFalse(
            "the branch isn't in the details",
            first.any {
                it.first.text.contains("7d3b7bc") && it.first.text.contains("main")
            },
        )

        val (avatar, avatarX) = first.single { it.first.icon != null && it.first.text.isNullOrEmpty() }
        assertTrue("the avatar shows", avatar.isVisible)
        assertTrue("the avatar is right of the branch", avatarX > branchX)
        val padding = component.width - (avatarX + avatar.width - avatar.insets.right)
        assertTrue("the avatar has room around it ($padding px to the edge)", avatar.insets.left > 0 && padding > 0)
    }

    private companion object {
        const val WIDTH = 500
        const val HEIGHT = 300
        const val NARROWER_BY = 120
        const val EDGE_SLACK = 24
        const val TIGHT_WIDTH = 160
    }
}
