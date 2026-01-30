package com.circleci.idea.toolwindow.tree

import com.intellij.ui.treeStructure.Tree
import javax.swing.SwingUtilities
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
                val identifier = getNodeIdentifier(node)
                if (identifier != null) {
                    expandedPaths.add(identifier)
                }
            }

            // Traverse children
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChildAt(i)
                traverseTree(path.pathByAddingChild(child))
            }
        }

        // Start from root
        val root = tree.model.root
        if (root != null) {
            traverseTree(TreePath(root))
        }

        return expandedPaths
    }

    /**
     * Restores the expansion state of the tree based on captured identifiers.
     */
    fun restoreState(
        tree: Tree,
        expandedIdentifiers: Set<String>,
    ) {
        SwingUtilities.invokeLater {
            fun expandMatchingNodes(path: TreePath) {
                val node = path.lastPathComponent as? CircleCITreeNode ?: return

                val identifier = getNodeIdentifier(node)
                if (identifier != null && identifier in expandedIdentifiers) {
                    tree.expandPath(path)
                }

                // Traverse children
                val count = node.childCount
                for (i in 0 until count) {
                    val child = node.getChildAt(i)
                    expandMatchingNodes(path.pathByAddingChild(child))
                }
            }

            // Start from root
            val root = tree.model.root
            if (root != null) {
                expandMatchingNodes(TreePath(root))
            }
        }
    }

    /**
     * Get a unique identifier for a node based on its type and data.
     */
    private fun getNodeIdentifier(node: CircleCITreeNode): String? {
        return when (node) {
            is ProjectNode -> "project:${node.project.slug}"
            is PipelineNode -> "pipeline:${node.pipeline.id}"
            is WorkflowNode -> "workflow:${node.workflow.id}"
            is JobNode -> "job:${node.job.id}"
            is RootNode -> "root"
            else -> null
        }
    }
}
