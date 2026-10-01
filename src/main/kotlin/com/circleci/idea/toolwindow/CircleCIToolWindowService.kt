package com.circleci.idea.toolwindow

import com.circleci.idea.toolwindow.tree.CircleCITreeModel
import com.circleci.idea.toolwindow.tree.CircleCITreeNode
import com.intellij.openapi.components.Service
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.treeStructure.Tree

/**
 * Service for managing the CircleCI tool window and its tree model.
 */
@Service(Service.Level.PROJECT)
class CircleCIToolWindowService {
    private var treeModel: CircleCITreeModel? = null
    private var tree: Tree? = null
    private var toolWindow: ToolWindow? = null

    fun setTreeModel(model: CircleCITreeModel) {
        this.treeModel = model
    }

    fun setTree(tree: Tree) {
        this.tree = tree
    }

    fun setToolWindow(toolWindow: ToolWindow) {
        this.toolWindow = toolWindow
    }

    fun getSelectedNode(): CircleCITreeNode? {
        val path = tree?.selectionPath ?: return null
        return path.lastPathComponent as? CircleCITreeNode
    }

    fun reloadTree() {
        treeModel?.reloadRoot()
    }

    fun refreshRuns() {
        treeModel?.refreshRuns()
    }
}
