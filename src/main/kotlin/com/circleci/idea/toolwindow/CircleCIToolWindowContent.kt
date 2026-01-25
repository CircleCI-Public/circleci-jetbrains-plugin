package com.circleci.idea.toolwindow

import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.project.CircleCIProjectService
import com.circleci.idea.toolwindow.tree.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.treeStructure.Tree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Content for the CircleCI tool window.
 * Displays a tree view of CircleCI projects, pipelines, workflows, and jobs.
 */
class CircleCIToolWindowContent(private val project: Project) : Disposable {
    private val panel = JBPanel<JBPanel<*>>(BorderLayout())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val treeModel = CircleCITreeModel(project, scope)
    private val tree = Tree(treeModel)
    private val projectService = project.getService(CircleCIProjectService::class.java)
    private val authService = CircleCIAuthService.getInstance(project)

    init {
        setupTree()
        setupToolbar()

        // Register tree model with service so RefreshAction can access it
        project.getService(CircleCIToolWindowService::class.java).setTreeModel(treeModel)

        // Restore authentication and initialize API client
        authService.restoreAuthentication()

        // Auto-detect projects if authenticated
        autoDetectProjects()

        // Note: Tree will auto-reload via StateFlow listeners in CircleCITreeModel
        // when projects are detected and selectedProjects is updated

        // Register for disposal
        Disposer.register(project, this)
    }

    private fun autoDetectProjects() {
        scope.launch {
            // Auto-detect projects from git repositories (doesn't require auth)
            projectService.detectProjects()

            // Fetch followed projects from CircleCI (requires auth - checked inside)
            projectService.fetchFollowedProjects()

            // Explicitly reload tree after detection
            treeModel.reloadRoot()
        }
    }

    /**
     * Public method to refresh the tree (called by RefreshAction).
     */
    fun refresh() {
        scope.launch {
            projectService.refresh()
            treeModel.reloadRoot()
        }
    }

    /**
     * Get the tree model for testing/debugging.
     */
    fun getTreeModel(): CircleCITreeModel = treeModel

    private fun setupTree() {
        val logger = com.circleci.idea.logging.CircleCILogger.getInstance()
        logger.info("Setting up tree: $tree")

        // Configure tree
        tree.cellRenderer = CircleCITreeCellRenderer()
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.isRootVisible = false
        tree.showsRootHandles = true

        logger.info("Tree configured: isRootVisible=${tree.isRootVisible}, showsRootHandles=${tree.showsRootHandles}")

        // Handle tree expansion
        tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? CircleCITreeNode ?: return
                if (node.canLoadChildren() && !node.childrenLoaded) {
                    treeModel.loadChildren(node)
                }
            }

            override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) {
                // Nothing to do on collapse
            }
        })

        // Handle double-click and "Load More" click
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    handleDoubleClick()
                } else if (e.clickCount == 1) {
                    handleSingleClick()
                }
            }
        })

        // Add tree to panel
        val scrollPane = ScrollPaneFactory.createScrollPane(tree)
        panel.add(scrollPane, BorderLayout.CENTER)
        logger.info("Tree added to panel in scrollPane. Panel component count: ${panel.componentCount}")
    }

    private fun setupToolbar() {
        val actionGroup = DefaultActionGroup()

        // Add actions
        actionGroup.add(com.circleci.idea.toolwindow.actions.AddProjectAction())
        actionGroup.add(com.circleci.idea.toolwindow.actions.RefreshAction())

        val toolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLBAR,
            actionGroup,
            true
        )
        toolbar.targetComponent = panel
        panel.add(toolbar.component, BorderLayout.NORTH)
    }

    private fun handleSingleClick() {
        val path = tree.selectionPath ?: return
        val node = path.lastPathComponent as? LoadMoreNode ?: return

        // Handle "Load More" click
        treeModel.loadMore(node)
    }

    private fun handleDoubleClick() {
        val path = tree.selectionPath ?: return
        val node = path.lastPathComponent as? CircleCITreeNode ?: return

        when (node) {
            is PipelineNode -> {
                // Future: Open pipeline in browser or details panel
            }
            is WorkflowNode -> {
                // Future: Open workflow in browser or details panel
            }
            is JobNode -> {
                // Future: Open job details panel
            }
            else -> {}
        }
    }

    fun getContent(): JComponent {
        return panel
    }

    override fun dispose() {
        scope.cancel()
    }
}
