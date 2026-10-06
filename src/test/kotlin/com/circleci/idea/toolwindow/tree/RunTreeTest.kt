package com.circleci.idea.toolwindow.tree

import com.circleci.idea.run.RunStatus
import com.circleci.idea.state.Run
import com.circleci.idea.state.Workflow
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RunTreeTest {
    private fun run(id: String) =
        Run(
            id = id,
            number = 3102,
            projectId = null,
            projectSlug = "gh/org/repo",
            repositoryName = "org/repo",
            status = RunStatus.SUCCESS,
            createdAt = null,
            branch = "main",
            tag = null,
            revision = null,
            commitSubject = "feat: a change",
            commitAuthor = null,
            triggeredBy = null,
        )

    private fun workflow(id: String) =
        Workflow(
            id = id,
            name = "build",
            status = RunStatus.SUCCESS,
            createdAt = null,
            endedAt = null,
            runId = "a",
            runNumber = 3102,
            projectSlug = "gh/org/repo",
        )

    /** Root → run a (loaded: a workflow, itself unloaded), run b (unloaded), load more. */
    private fun tree(): RootNode {
        val root = RootNode()
        root.add(
            RunNode(run("a")).also {
                it.add(WorkflowNode(workflow("w")))
                it.childrenLoaded = true
            },
        )
        root.add(RunNode(run("b")))
        root.add(LoadMoreNode())
        return root
    }

    /** Every element of a Jewel tree, opened and depth first, as "key" lines indented by depth. */
    private fun render(tree: Tree<CircleCITreeNode>): List<String> {
        val lines = mutableListOf<String>()

        fun walk(elements: List<Tree.Element<CircleCITreeNode>>) {
            for (element in elements) {
                lines.add("  ".repeat(element.depth) + element.id)
                if (element is Tree.Element.Node) {
                    // A node's children are generated as it's opened.
                    element.open()
                    walk(element.children.orEmpty())
                }
            }
        }
        walk(tree.roots)
        return lines
    }

    @Test
    fun testKeysFollowWhatTheRowsShow() {
        val root = tree()
        val run = root.getChildAt(0) as CircleCITreeNode

        assertEquals("a run by its id", "run:a", keyOf(run))
        assertEquals("a workflow by its id", "workflow:w", keyOf(run.getChildAt(0) as CircleCITreeNode))
        assertEquals("a message by its place", "root/2", keyOf(root.getChildAt(2) as CircleCITreeNode))
        assertEquals("a rebuilt run keys as before", "run:a", keyOf(RunNode(run("a"))))
    }

    @Test
    fun testTreeShowsTheRootsChildren() {
        assertEquals(
            "runs and the load more row at the top; a workflow under its run",
            listOf("run:a", "  workflow:w", "run:b", "root/2"),
            render(runTree(tree())),
        )
    }

    @Test
    fun testFindsNodesByKey() {
        val root = tree()

        assertSame("a workflow", root.getChildAt(0).getChildAt(0), findNode(root, "workflow:w"))
        assertNull("gone", findNode(root, "run:gone"))
    }

    @Test
    fun testLoadsOnlyOpenNodesWithNothingLoaded() {
        val root = tree()
        val runA = root.getChildAt(0) as RunNode
        val runB = root.getChildAt(1) as RunNode

        assertEquals("nothing open", emptyList<CircleCITreeNode>(), nodesToLoad(root, emptySet()))
        assertEquals(
            "an open run with no workflows yet, but not one already loaded",
            listOf(runB),
            nodesToLoad(root, setOf("run:a", "run:b")),
        )
        assertEquals(
            "an open workflow under an open run",
            listOf(runA.getChildAt(0)),
            nodesToLoad(root, setOf("run:a", "workflow:w")),
        )
        assertEquals("not under a closed run", emptyList<CircleCITreeNode>(), nodesToLoad(root, setOf("workflow:w")))

        runB.add(ErrorNode("boom"))
        assertEquals(
            "not one showing why it failed",
            emptyList<CircleCITreeNode>(),
            nodesToLoad(root, setOf("run:b")),
        )
    }

    @Test
    fun testTitleIsNotCappedInLength() {
        val subject = "feat: " + "a long commit subject ".repeat(6).trim()
        val node = RunNode(run("a").copy(commitSubject = subject))
        assertEquals("the whole subject, for the label to fit to the room", "#3102 $subject", node.getDisplayText())
    }

    @Test
    fun testChildrenOfAnEndedParentThatHaveAllEndedAreFinal() {
        assertTrue("all ended", childrenFinal(true, listOf(RunStatus.SUCCESS, RunStatus.FAILED, RunStatus.NOT_RUN)))
        assertTrue("none at all", childrenFinal(true, emptyList()))
    }

    @Test
    fun testChildrenStillActiveAreNotFinalThoughTheirParentEnded() {
        // The run listing can say a run ended while its workflows still say running.
        assertFalse("one still running", childrenFinal(true, listOf(RunStatus.SUCCESS, RunStatus.RUNNING)))
        assertFalse("one on hold", childrenFinal(true, listOf(RunStatus.ON_HOLD)))
        assertFalse("one queued", childrenFinal(true, listOf(RunStatus.QUEUED)))
    }

    @Test
    fun testChildrenOfAParentStillActiveAreNotFinal() {
        // A run still going can start another workflow, as a setup workflow does.
        assertFalse("all ended so far", childrenFinal(false, listOf(RunStatus.SUCCESS)))
    }
}
