package com.circleci.idea.toolwindow

import com.circleci.idea.toolwindow.tree.CircleCITreeModel
import com.circleci.idea.toolwindow.tree.CircleCITreeNode
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree

/**
 * Service for managing the CircleCI tool window and its tree model.
 */
@Service(Service.Level.PROJECT)
class CircleCIToolWindowService(private val project: Project) {
    private var treeModel: CircleCITreeModel? = null
    private var tree: Tree? = null
    private var toolWindow: ToolWindow? = null
    private var jobDetailsPanel: JobDetailsPanel? = null

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

    fun refreshPipelines() {
        treeModel?.refreshPipelines()
    }

    /**
     * Show the job details panel.
     * Creates the panel if it doesn't exist and switches to it.
     */
    fun showJobDetailsPanel() {
        val tw = toolWindow ?: return

        // Check if job details panel already exists
        val existingContent = tw.contentManager.contents.find { it.displayName == "Job Details" }

        if (existingContent != null) {
            // Panel exists, just select it
            tw.contentManager.setSelectedContent(existingContent)
        } else {
            // Create new job details panel
            val panel = JobDetailsPanel(project)
            jobDetailsPanel = panel

            val content =
                ContentFactory.getInstance().createContent(
                    panel,
                    "Job Details",
                    false,
                )

            tw.contentManager.addContent(content)
            tw.contentManager.setSelectedContent(content)
        }
    }
}
