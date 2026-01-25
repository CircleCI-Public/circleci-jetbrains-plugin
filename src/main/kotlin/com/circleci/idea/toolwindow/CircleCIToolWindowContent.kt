package com.circleci.idea.toolwindow

import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.job.JobDetailsService
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
    private val jobDetailsService = project.getService(JobDetailsService::class.java)

    init {
        setupTree()
        setupToolbar()

        // Register tree and tree model with service
        val toolWindowService = project.getService(CircleCIToolWindowService::class.java)
        toolWindowService.setTreeModel(treeModel)
        toolWindowService.setTree(tree)

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

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showContextMenu(e)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showContextMenu(e)
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

        // Project management actions
        actionGroup.add(com.circleci.idea.toolwindow.actions.AddProjectAction())
        actionGroup.add(com.circleci.idea.toolwindow.actions.RefreshAction())
        actionGroup.addSeparator()

        // Filter actions
        actionGroup.add(com.circleci.idea.toolwindow.actions.BranchFilterAction())
        actionGroup.add(com.circleci.idea.toolwindow.actions.StatusFilterAction())
        actionGroup.add(com.circleci.idea.toolwindow.actions.MyPipelinesOnlyAction())

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
                handleJobDoubleClick(node)
            }
            else -> {}
        }
    }

    private fun handleJobDoubleClick(jobNode: JobNode) {
        val job = jobNode.job
        scope.launch {
            jobDetailsService.selectAndFetchJobDetails(
                jobId = job.id,
                jobNumber = job.jobNumber,
                projectSlug = job.projectSlug
            )
        }

        // Show job details panel in tool window service
        project.getService(CircleCIToolWindowService::class.java).showJobDetailsPanel()
    }

    private fun showContextMenu(e: MouseEvent) {
        // Select the node under the mouse
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        tree.selectionPath = path

        val node = path.lastPathComponent as? CircleCITreeNode ?: return

        // Create context menu based on node type
        val actionGroup = DefaultActionGroup()

        when (node) {
            is WorkflowNode -> {
                actionGroup.add(com.circleci.idea.toolwindow.actions.RerunWorkflowAction())
                actionGroup.add(com.circleci.idea.toolwindow.actions.RerunWorkflowFromFailedAction())
                actionGroup.add(com.circleci.idea.toolwindow.actions.CancelWorkflowAction())
                actionGroup.addSeparator()
                actionGroup.add(com.circleci.idea.toolwindow.actions.ApproveWorkflowAction())
                actionGroup.addSeparator()
                actionGroup.add(com.circleci.idea.toolwindow.actions.OpenWorkflowInBrowserAction())
            }
            is JobNode -> {
                actionGroup.add(com.circleci.idea.toolwindow.actions.OpenJobDetailsAction())
                actionGroup.addSeparator()
                actionGroup.add(com.circleci.idea.toolwindow.actions.RerunWorkflowFromJobAction())
                actionGroup.add(com.circleci.idea.toolwindow.actions.RerunJobWithSshAction())
                actionGroup.add(com.circleci.idea.toolwindow.actions.CancelJobAction())
                actionGroup.addSeparator()
                actionGroup.add(com.circleci.idea.toolwindow.actions.CopyJobNumberAction())
                actionGroup.add(com.circleci.idea.toolwindow.actions.OpenJobInBrowserAction())
            }
            is PipelineNode -> {
                // Future: Add pipeline actions
            }
            else -> {
                // No context menu for other node types
                return
            }
        }

        // Show popup menu
        val popupMenu = ActionManager.getInstance().createActionPopupMenu(
            ActionPlaces.TOOLWINDOW_POPUP,
            actionGroup
        )
        popupMenu.component.show(e.component, e.x, e.y)
    }

    fun getContent(): JComponent {
        return panel
    }

    override fun dispose() {
        scope.cancel()
    }
}
