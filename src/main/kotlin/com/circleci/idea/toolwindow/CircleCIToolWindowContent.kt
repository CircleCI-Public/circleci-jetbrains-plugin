package com.circleci.idea.toolwindow

import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.job.JobDetailsService
import com.circleci.idea.job.JobRef
import com.circleci.idea.polling.RunPollingService
import com.circleci.idea.project.CircleCIProjectService
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.toolwindow.tree.CircleCITreeCellRenderer
import com.circleci.idea.toolwindow.tree.CircleCITreeModel
import com.circleci.idea.toolwindow.tree.CircleCITreeNode
import com.circleci.idea.toolwindow.tree.JobNode
import com.circleci.idea.toolwindow.tree.LoadMoreNode
import com.circleci.idea.toolwindow.tree.RunNode
import com.circleci.idea.toolwindow.tree.WorkflowNode
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import com.intellij.vcs.ui.ProgressStripe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.tree.TreeSelectionModel

/**
 * Content for the CircleCI tool window.
 * Displays a tree view of CircleCI projects, runs, workflows, and jobs.
 */
class CircleCIToolWindowContent(private val project: Project) : Disposable {
    private val panel = JBPanel<JBPanel<*>>(BorderLayout())

    // The runs, or the signed-out view in their place until you log in.
    private val cards = CardLayout()
    private val root = JBPanel<JBPanel<*>>(cards)
    private val signedOutView = SignedOutView(project)
    private var signedIn: Boolean? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val treeModel = CircleCITreeModel(project, scope)
    private val tree = Tree(treeModel)
    private val projectService = project.getService(CircleCIProjectService::class.java)
    private val authService = CircleCIAuthService.getInstance(project)
    private val jobDetailsService = project.getService(JobDetailsService::class.java)
    private val pollingService = project.getService(RunPollingService::class.java)
    private val stateStore = CircleCIStateStore.getInstance(project)

    init {
        setupTree()
        setupToolbar()

        // Set tree reference in tree model for state management
        treeModel.setTree(tree)

        // Register tree and tree model with service
        val toolWindowService = project.getService(CircleCIToolWindowService::class.java)
        toolWindowService.setTreeModel(treeModel)
        toolWindowService.setTree(tree)

        root.add(panel, RUNS_CARD)
        root.add(signedOutView.component, SIGNED_OUT_CARD)

        // Restore authentication and initialize API client
        authService.restoreAuthentication()

        // Auto-detect projects if authenticated
        autoDetectProjects()

        observeAuth()

        // Note: Tree will auto-reload via StateFlow listeners in CircleCITreeModel
        // when projects are detected and selectedProject is updated

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

            // Start polling for run updates
            pollingService.startPolling()
        }
    }

    /**
     * Show the runs when there's a token that hasn't been rejected, and the
     * signed-out view otherwise; reload the runs on logging in.
     */
    private fun observeAuth() {
        scope.launch {
            stateStore.auth.collect { auth ->
                // Reading the stored token goes to the credential store: keep it off the EDT.
                val hasToken = withContext(Dispatchers.IO) { authService.getToken() != null }
                val nowSignedIn = auth.isAuthenticated || (hasToken && auth.error == null)
                signedOutView.showError(auth.error.takeIf { !nowSignedIn })
                if (nowSignedIn == signedIn) return@collect

                val wasSignedOut = signedIn == false
                signedIn = nowSignedIn
                cards.show(root, if (nowSignedIn) RUNS_CARD else SIGNED_OUT_CARD)
                if (nowSignedIn && wasSignedOut) autoDetectProjects()
            }
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
        // Rows take their renderer's height: runs are two lines, everything else one.
        tree.rowHeight = 0
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.isRootVisible = false
        tree.showsRootHandles = true

        logger.info("Tree configured: isRootVisible=${tree.isRootVisible}, showsRootHandles=${tree.showsRootHandles}")

        // Handle tree expansion
        tree.addTreeExpansionListener(
            object : javax.swing.event.TreeExpansionListener {
                override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) {
                    val node = event.path.lastPathComponent as? CircleCITreeNode ?: return
                    if (node.canLoadChildren() && !node.childrenLoaded) {
                        treeModel.loadChildren(node)
                    }
                }

                override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) {
                    // Nothing to do on collapse
                }
            },
        )

        // Handle double-click and "Load More" click
        tree.addMouseListener(
            object : MouseAdapter() {
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
            },
        )

        // Add tree to panel
        tree.emptyText.text = "Loading runs..."
        val scrollPane = ScrollPaneFactory.createScrollPane(tree)
        // Run rows span the visible width (their avatars sit at its right edge), so re-measure them when it changes.
        scrollPane.viewport.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = TreeUtil.invalidateCacheAndRepaint(tree.ui)
            },
        )
        // A thin progress bar along the top while runs load, as the Pull Requests list has.
        val loadingStripe = ProgressStripe(scrollPane, this)
        scope.launch {
            treeModel.loading.collect {
                    loading ->
                if (loading) loadingStripe.startLoading() else loadingStripe.stopLoading()
            }
        }
        panel.add(loadingStripe, BorderLayout.CENTER)
        logger.info("Tree added to panel in scrollPane. Panel component count: ${panel.componentCount}")
    }

    private fun setupToolbar() {
        val filterBar = RunFilterBar(project, scope) { treeModel.reloadRoot() }
        panel.add(filterBar, BorderLayout.NORTH)
    }

    /**
     * Actions for the tool window's title bar: the project whose runs to list,
     * then refreshing, as the Pull Requests list keeps its refresh there.
     */
    fun titleActions(): List<AnAction> =
        listOf(
            com.circleci.idea.toolwindow.actions.ProjectChooserAction(),
            com.circleci.idea.toolwindow.actions.RefreshAction(),
            com.circleci.idea.toolwindow.actions.ToggleAutoRefreshAction(),
        )

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
            is JobNode -> {
                handleJobDoubleClick(node)
            }
            else -> {}
        }
    }

    private fun handleJobDoubleClick(jobNode: JobNode) {
        val workflowNode = jobNode.parent as? WorkflowNode
        jobDetailsService.openJob(JobRef.of(jobNode.job, workflowNode?.workflow))
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
                actionGroup.add(com.circleci.idea.toolwindow.actions.RerunWorkflowWithSshAction())
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
            is RunNode -> {
                actionGroup.add(com.circleci.idea.toolwindow.actions.OpenRunInBrowserAction())
            }
            else -> {
                // No context menu for other node types
                return
            }
        }

        // Show popup menu
        val popupMenu =
            ActionManager.getInstance().createActionPopupMenu(
                ActionPlaces.TOOLWINDOW_POPUP,
                actionGroup,
            )
        popupMenu.component.show(e.component, e.x, e.y)
    }

    fun getContent(): JComponent {
        return root
    }

    override fun dispose() {
        pollingService.stopPolling()
        scope.cancel()
    }

    private companion object {
        const val RUNS_CARD = "runs"
        const val SIGNED_OUT_CARD = "signed-out"
    }
}
