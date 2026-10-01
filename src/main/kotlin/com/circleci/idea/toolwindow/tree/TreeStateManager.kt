package com.circleci.idea.toolwindow.tree

import com.intellij.ui.treeStructure.Tree
import javax.swing.tree.TreeNode
import javax.swing.tree.TreePath

/**
 * Manages tree expansion state across reloads.
 * Captures which nodes are expanded and restores them after tree refresh.
 */
class TreeStateManager {
    /**
     * Captures the current expansion state of the tree.
     * Returns a set of node identifiers that are currently expanded.
     */
    fun captureState(tree: Tree): Set<String> {
        val expandedPaths = mutableSetOf<String>()

        fun traverseTree(path: TreePath) {
            val node = path.lastPathComponent as? CircleCITreeNode ?: return

            if (tree.isExpanded(path)) {
                getNodeIdentifier(node)?.let { expandedPaths.add(it) }
            }

            for (i in 0 until node.childCount) {
                traverseTree(path.pathByAddingChild(node.getChildAt(i)))
            }
        }

        tree.model.root?.let { traverseTree(TreePath(it)) }
        return expandedPaths
    }

    /**
     * Expands the nodes at and below [node] whose identifiers are in
     * [expandedIdentifiers]. Must be called on the EDT.
     */
    fun restoreState(
        tree: Tree,
        node: CircleCITreeNode,
        expandedIdentifiers: Set<String>,
    ) {
        if (expandedIdentifiers.isEmpty()) return

        // Expanding a node scrolls its children into view; putting back the
        // tree the user had shouldn't move it.
        val scrollsOnExpand = tree.scrollsOnExpand
        tree.scrollsOnExpand = false
        try {
            expandMatching(tree, node, expandedIdentifiers)
        } finally {
            tree.scrollsOnExpand = scrollsOnExpand
        }
    }

    private fun expandMatching(
        tree: Tree,
        node: CircleCITreeNode,
        expandedIdentifiers: Set<String>,
    ) {
        fun expandMatchingNodes(path: TreePath) {
            val current = path.lastPathComponent as? CircleCITreeNode ?: return

            if (getNodeIdentifier(current) in expandedIdentifiers) {
                tree.expandPath(path)
            }

            for (i in 0 until current.childCount) {
                expandMatchingNodes(path.pathByAddingChild(current.getChildAt(i)))
            }
        }

        expandMatchingNodes(pathTo(node))
    }

    /**
     * The identifiers of every node in the tree, expanded or not.
     */
    fun captureIdentifiers(tree: Tree): Set<String> {
        val root = tree.model.root as? CircleCITreeNode ?: return emptySet()
        return root.depthFirstEnumeration().toList().mapNotNull {
            (it as? CircleCITreeNode)?.let(
                ::getNodeIdentifier,
            )
        }.toSet()
    }

    /**
     * The identifier of the selected node, if any.
     */
    fun captureSelection(tree: Tree): String? {
        return (tree.selectionPath?.lastPathComponent as? CircleCITreeNode)?.let { getNodeIdentifier(it) }
    }

    /**
     * Reselects the node at or below [node] with the identifier [selected],
     * when nothing else has been selected since. Must be called on the EDT.
     */
    fun restoreSelection(
        tree: Tree,
        node: CircleCITreeNode,
        selected: String?,
    ) {
        if (selected == null || tree.selectionPath != null) return

        fun find(current: CircleCITreeNode): CircleCITreeNode? {
            if (getNodeIdentifier(current) == selected) return current
            val children = current.children().asSequence().filterIsInstance<CircleCITreeNode>()
            return children.firstNotNullOfOrNull { find(it) }
        }

        find(node)?.let { tree.selectionPath = pathTo(it) }
    }

    private fun pathTo(node: TreeNode): TreePath {
        val nodes = generateSequence(node) { it.parent }.toList().asReversed()
        return TreePath(nodes.toTypedArray())
    }

    /**
     * Get a unique identifier for a node based on its type and data.
     */
    private fun getNodeIdentifier(node: CircleCITreeNode): String? {
        return when (node) {
            is ProjectNode -> "project:${node.project.slug}"
            is MyRunsNode -> "my-runs"
            is RunNode -> "run:${node.run.id}"
            is WorkflowNode -> "workflow:${node.workflow.id}"
            is JobNode -> "job:${node.job.id}"
            is RootNode -> "root"
            else -> null
        }
    }
}
