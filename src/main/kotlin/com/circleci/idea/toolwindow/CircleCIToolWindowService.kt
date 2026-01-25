package com.circleci.idea.toolwindow

import com.circleci.idea.toolwindow.tree.CircleCITreeModel
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Service for managing the CircleCI tool window and its tree model.
 */
@Service(Service.Level.PROJECT)
class CircleCIToolWindowService(private val project: Project) {
    private var treeModel: CircleCITreeModel? = null

    fun setTreeModel(model: CircleCITreeModel) {
        this.treeModel = model
    }

    fun reloadTree() {
        treeModel?.reloadRoot()
    }
}
