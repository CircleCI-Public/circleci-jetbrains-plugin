package com.circleci.idea.toolwindow.tree

import com.circleci.idea.project.models.CircleCIProject
import com.circleci.idea.project.models.VcsType
import com.circleci.idea.run.RunStatus
import com.circleci.idea.state.Run
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.treeStructure.Tree
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class TreeStateManagerTest : BasePlatformTestCase() {
    private val manager = TreeStateManager()

    private fun run(id: String) =
        Run(
            id = id,
            number = 1,
            projectId = null,
            projectSlug = "gh/org/repo",
            repositoryName = "org/repo",
            status = RunStatus.SUCCESS,
            createdAt = null,
            branch = "main",
            tag = null,
            revision = null,
            commitSubject = null,
            commitAuthor = null,
            triggeredBy = null,
        )

    /** A root → project → two runs tree, each built fresh as a reload builds it. */
    private fun buildTree(): Pair<RootNode, ProjectNode> {
        val root = RootNode()
        val project =
            ProjectNode(
                CircleCIProject(
                    slug = "gh/org/repo",
                    vcsType = VcsType.GITHUB,
                    organization = "org",
                    repository = "repo",
                ),
            )
        root.add(project)
        project.add(RunNode(run("a")).also { it.add(EmptyNode("No workflows")) })
        project.add(RunNode(run("b")).also { it.add(EmptyNode("No workflows")) })
        return root to project
    }

    fun testRestoresExpansionAndSelectionOntoRebuiltNodes() {
        val (root, project) = buildTree()
        val tree = Tree(DefaultTreeModel(root))
        tree.expandPath(TreePath(arrayOf(root, project)))
        tree.expandPath(TreePath(arrayOf(root, project, project.getChildAt(1))))
        tree.selectionPath = TreePath(arrayOf(root, project, project.getChildAt(1)))

        val expanded = manager.captureState(tree)
        val selected = manager.captureSelection(tree)
        assertEquals("selected run", "run:b", selected)
        assertTrue("every node is known", manager.captureIdentifiers(tree).containsAll(setOf("run:a", "run:b")))

        val (newRoot, newProject) = buildTree()
        (tree.model as DefaultTreeModel).setRoot(newRoot)
        tree.scrollsOnExpand = true
        manager.restoreState(tree, newRoot, expanded)
        manager.restoreSelection(tree, newRoot, selected)

        assertTrue("project expanded", tree.isExpanded(TreePath(arrayOf(newRoot, newProject))))
        assertTrue("run b expanded", tree.isExpanded(TreePath(arrayOf(newRoot, newProject, newProject.getChildAt(1)))))
        assertFalse(
            "run a still collapsed",
            tree.isExpanded(TreePath(arrayOf(newRoot, newProject, newProject.getChildAt(0)))),
        )
        assertEquals("selection restored", "run:b", manager.captureSelection(tree))
        assertTrue("scrolling on expand is put back", tree.scrollsOnExpand)
    }

    fun testDoesNotOverrideANewSelection() {
        val (root, project) = buildTree()
        val tree = Tree(DefaultTreeModel(root))
        tree.selectionPath = TreePath(arrayOf(root, project, project.getChildAt(0)))

        manager.restoreSelection(tree, root, "run:b")

        assertEquals("the user's selection stands", "run:a", manager.captureSelection(tree))
    }
}
