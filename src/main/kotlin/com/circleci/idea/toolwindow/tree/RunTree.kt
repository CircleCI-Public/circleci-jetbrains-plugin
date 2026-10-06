package com.circleci.idea.toolwindow.tree

import com.circleci.idea.run.RunStatus
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.TreeGeneratorScope
import org.jetbrains.jewel.foundation.lazy.tree.buildTree

/*
 * The run tree as Jewel's tree draws it, built from the nodes the model
 * loads. Rows are keyed by what they show (a run, workflow or job id) rather
 * than by node, so what's open and selected carries over to the nodes a
 * reload rebuilds.
 */

/**
 * A node's key in the tree: its run, workflow or job, or for a message
 * (loading, empty, error, more to load) its place under its parent.
 */
internal fun keyOf(node: CircleCITreeNode): String =
    when (node) {
        is RootNode -> "root"
        is RunNode -> "run:${node.run.id}"
        is WorkflowNode -> "workflow:${node.workflow.id}"
        is JobNode -> "job:${node.job.id}"
        else -> messageKey(node)
    }

/** A message, by its place under its parent. */
private fun messageKey(node: CircleCITreeNode): String {
    val parent = node.parent as? CircleCITreeNode ?: return "detached:${node.hashCode()}"
    return "${keyOf(parent)}/${parent.getIndex(node)}"
}

/** The root's children as a tree to draw; the root itself isn't shown. */
internal fun runTree(root: RootNode): Tree<CircleCITreeNode> = buildTree { addChildren(root) }

private fun TreeGeneratorScope<CircleCITreeNode>.addChildren(node: CircleCITreeNode) {
    for (child in children(node)) {
        if (child.canLoadChildren()) {
            addNode(child, keyOf(child)) { addChildren(child) }
        } else {
            addLeaf(child, keyOf(child))
        }
    }
}

/** The node under [root] with [key], if it's still in the tree. */
internal fun findNode(
    root: CircleCITreeNode,
    key: Any,
): CircleCITreeNode? {
    if (keyOf(root) == key) return root
    return children(root).firstNotNullOfOrNull { findNode(it, key) }
}

/**
 * The open nodes, among those showing, whose children have yet to load:
 * opened for the first time, or rebuilt by a reload while open.
 *
 * A node that's loading has a loading row, and one that failed an error
 * row, so neither is offered again until it's closed.
 */
internal fun nodesToLoad(
    root: CircleCITreeNode,
    open: Set<Any>,
): List<CircleCITreeNode> =
    children(root).flatMap { child ->
        when {
            keyOf(child) !in open -> emptyList()
            child.canLoadChildren() && !child.childrenLoaded && child.childCount == 0 -> listOf(child)
            else -> nodesToLoad(child, open)
        }
    }

/**
 * Whether children just fetched won't change, so a refresh can keep them:
 * their parent had ended before they were fetched, and they've all ended
 * too. The parent's status comes from a different listing, which can say it
 * ended before its children do.
 */
internal fun childrenFinal(
    parentEnded: Boolean,
    children: List<RunStatus>,
): Boolean = parentEnded && children.none { it.isActive }

internal fun children(node: CircleCITreeNode): List<CircleCITreeNode> =
    node.children().toList().filterIsInstance<CircleCITreeNode>()
