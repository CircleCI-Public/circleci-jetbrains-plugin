package com.circleci.idea.toolwindow.tree

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.api.clients.V3Page
import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.polling.RunPollingService
import com.circleci.idea.project.CircleCIProjectService
import com.circleci.idea.run.RunListService
import com.circleci.idea.run.RunScope
import com.circleci.idea.settings.CircleCISettings
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.state.Run
import com.intellij.openapi.project.Project
import com.intellij.ui.treeStructure.Tree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Tree model for the CircleCI tree view: projects (or "My runs"), their runs,
 * each run's workflows and each workflow's jobs.
 *
 * Children load asynchronously when a node is first expanded. A refresh
 * re-fetches every loaded level in place, keeping the nodes (and so the
 * expansion and selection) of runs and workflows that are still listed.
 *
 * All node mutation happens on the EDT: the coroutine [scope] runs on
 * Dispatchers.Main, and the fetches switch to IO themselves.
 */
class CircleCITreeModel(
    private val project: Project,
    private val scope: CoroutineScope,
) : DefaultTreeModel(RootNode()) {
    private val logger = CircleCILogger.getInstance()
    private val projectService = project.getService(CircleCIProjectService::class.java)
    private val apiService = CircleCIApiService.getInstance()
    private val authService = CircleCIAuthService.getInstance(project)
    private val settings = CircleCISettings.getInstance()
    private val stateStore = CircleCIStateStore.getInstance(project)
    private val runListService = RunListService.getInstance(project)
    private val pollingService = project.getService(RunPollingService::class.java)
    private val stateManager = TreeStateManager()
    private var tree: Tree? = null

    // Nodes expanded, and the node selected, before the root was last
    // rebuilt. The rebuilt nodes load asynchronously, so these are reapplied
    // as each level's children arrive.
    private var expandedBeforeReload: Set<String> = emptySet()
    private var selectedBeforeReload: String? = null

    init {
        // Listen to project changes and reload root
        scope.launch {
            projectService.selectedProject.collect { slug ->
                logger.info("StateFlow: selectedProject changed to $slug")
                reloadRoot()
            }
        }

        // Also listen to projects being populated (in case selection was persisted before detection)
        scope.launch {
            projectService.projects.collect { allProjects ->
                logger.info("StateFlow: projects changed, size=${allProjects.size}")
                reloadRoot()
            }
        }
    }

    /**
     * Set the tree instance for state management.
     * Must be called before reloadRoot() to enable state preservation.
     */
    fun setTree(tree: Tree) {
        this.tree = tree
    }

    /**
     * Re-fetch every loaded level of the tree in place. A run list whose last
     * load failed is retried.
     */
    fun refreshRuns() {
        SwingUtilities.invokeLater {
            if (runFetcher() != null) loadRuns(refresh = true)
        }
    }

    /**
     * Rebuild the tree from the selected projects and the current filters.
     */
    fun reloadRoot() {
        SwingUtilities.invokeLater {
            val tree = tree
            if (tree != null) {
                // A reload can come before the last one's nodes have all
                // loaded (the project list and the selection change together
                // on startup, say), so keep what's still waiting to be put back.
                val present = stateManager.captureIdentifiers(tree)
                expandedBeforeReload = stateManager.captureState(tree) + (expandedBeforeReload - present)
                selectedBeforeReload = stateManager.captureSelection(tree) ?: selectedBeforeReload
            }
            val hadFocus = tree?.hasFocus() == true

            val rootNode = root as RootNode
            if (runFetcher() != null) {
                loadRuns(refresh = false)
            } else {
                rootNode.loadGeneration++
                rootNode.childrenLoaded = false
                rootNode.removeAllChildren()
                rootNode.add(EmptyNode("No CircleCI project found in this workspace"))
                reload(rootNode)
            }

            if (tree != null && hadFocus) tree.requestFocusInWindow()
        }
    }

    /**
     * Override to indicate which nodes are leaves.
     */
    override fun isLeaf(node: Any?): Boolean {
        return when (node) {
            is CircleCITreeNode -> !node.canLoadChildren()
            else -> super.isLeaf(node)
        }
    }

    /**
     * Load children for a given node asynchronously.
     */
    fun loadChildren(node: CircleCITreeNode) {
        if (!node.childrenLoaded) {
            loadChildren(node, refresh = false)
        }
    }

    /**
     * Load the next page of runs in place of [loadMoreNode].
     */
    fun loadMore(loadMoreNode: LoadMoreNode) {
        val parent = loadMoreNode.parent as? RootNode ?: return
        val fetch = runFetcher() ?: return
        val generation = parent.loadGeneration

        val index = parent.getIndex(loadMoreNode)
        parent.remove(loadMoreNode)
        val loadingNode = LoadingNode()
        parent.insert(loadingNode, index)
        nodeStructureChanged(parent)

        scope.launch {
            val result = authenticated { fetch(loadMoreNode.nextCursor) }
            // A refresh or reload replaced the list while this page was loading.
            if (generation != parent.loadGeneration || loadingNode.parent !== parent) return@launch

            val expanded = captureExpansion()
            val selected = tree?.let { stateManager.captureSelection(it) }
            parent.remove(loadingNode)
            result.fold(
                onSuccess = { page ->
                    page.items.forEach { parent.add(RunNode(it, showProject = isMyRuns())) }
                    page.nextCursor?.let { parent.add(LoadMoreNode(it)) }
                },
                onFailure = { error ->
                    logger.error("Failed to load more runs: ${error.message}", error)
                    parent.add(ErrorNode(error.message ?: "Failed to load more runs"))
                },
            )
            nodeStructureChanged(parent)
            tree?.let { stateManager.restoreState(it, parent, expanded) }
            restoreSelection(parent, selected)
        }
    }

    private fun loadChildren(
        node: CircleCITreeNode,
        refresh: Boolean,
    ) {
        when (node) {
            is RootNode -> loadRuns(refresh)
            is RunNode -> loadWorkflows(node, refresh)
            is WorkflowNode -> loadJobs(node, refresh)
            else -> {}
        }
    }

    private fun isMyRuns(): Boolean = stateStore.filters.value.scope == RunScope.MY_RUNS

    /**
     * Fetches a page of the runs the tree lists: the user's own across every
     * project, or the selected project's. Null when there's no project to list.
     */
    private fun runFetcher(): (suspend (String?) -> Result<V3Page<Run>>)? {
        if (isMyRuns()) return { cursor -> runListService.fetchMyRuns(cursor) }
        val selected = projectService.getSelectedProject() ?: return null
        return { cursor -> runListService.fetchProjectRuns(selected, cursor) }
    }

    /** List the runs at the top of the tree. */
    private fun loadRuns(refresh: Boolean) {
        val fetch = runFetcher() ?: return
        val node = root as RootNode
        val myRuns = isMyRuns()
        loadInto(node, refresh, "runs", { fetch(null) }) { page, previous ->
            val existing = existingChildren<RunNode, String>(previous) { it.run.id }
            if (page.items.isEmpty()) {
                node.add(EmptyNode(emptyRunsMessage()))
            }
            page.items.forEach { run ->
                val reused = existing[run.id]
                if (reused != null && reused.showProject == myRuns) {
                    // Keep a slug the workflows load resolved, which the listing lacks.
                    reused.run = run.copy(projectSlug = run.projectSlug ?: reused.run.projectSlug)
                    node.add(reused)
                } else {
                    node.add(RunNode(run, showProject = myRuns))
                }
            }
            page.nextCursor?.let { node.add(LoadMoreNode(it)) }

            page.items.firstNotNullOfOrNull { it.createdAt }?.let { pollingService.updateNewestRunTime(it) }
            page.items.filter { it.projectSlug != null }.groupBy { it.projectSlug!! }.forEach { (slug, runs) ->
                stateStore.setRuns(slug, runs, page.nextCursor.takeIf { !myRuns })
            }
        }
    }

    private fun loadWorkflows(
        node: RunNode,
        refresh: Boolean,
    ) {
        loadInto(
            node,
            refresh,
            "workflows",
            { runListService.fetchWorkflows(node.run) },
        ) { (run, workflows), previous ->
            node.run = run
            val existing = existingChildren<WorkflowNode, String>(previous) { it.workflow.id }
            if (workflows.isEmpty()) {
                node.add(
                    EmptyNode(
                        if (run.errors.isNotEmpty()) "No workflows: ${run.errors.first().message}" else "No workflows",
                    ),
                )
            }
            workflows.forEach { workflow ->
                val reused = existing[workflow.id]
                if (reused != null) {
                    reused.workflow = workflow
                    node.add(reused)
                } else {
                    node.add(WorkflowNode(workflow))
                }
            }
            run.projectSlug?.let { stateStore.updateWorkflows(it, run.id, workflows) }
        }
    }

    private fun loadJobs(
        node: WorkflowNode,
        refresh: Boolean,
    ) {
        loadInto(node, refresh, "jobs", { runListService.fetchJobs(node.workflow) }) { jobs, _ ->
            if (jobs.isEmpty()) {
                node.add(EmptyNode("No jobs"))
            }
            jobs.forEach { node.add(JobNode(it)) }
        }
    }

    /**
     * Fetch a node's children and replace them with the result.
     *
     * A first load shows a loading row while it fetches. A [refresh] keeps the
     * current rows until the result arrives, then — since some children may be
     * kept by [populate] — re-fetches the expanded ones beneath it in turn.
     */
    private fun <T> loadInto(
        node: CircleCITreeNode,
        refresh: Boolean,
        what: String,
        fetch: suspend () -> Result<T>,
        // Given the result and the node's children before it, some of which it may keep.
        populate: (T, List<CircleCITreeNode>) -> Unit,
    ) {
        val generation = ++node.loadGeneration
        if (!refresh) {
            node.removeAllChildren()
            node.add(LoadingNode())
            nodeStructureChanged(node)
        }

        scope.launch {
            val result = authenticated(fetch)
            // Stale, or for a node no longer in the tree.
            if (generation != node.loadGeneration || (node !== root && node.parent == null)) return@launch
            if (refresh && result.isFailure) {
                // Keep showing what loaded last; the next refresh tries again.
                logger.warn("Failed to refresh $what: ${result.exceptionOrNull()?.message}")
                return@launch
            }

            val expanded = captureExpansion()
            val selected = tree?.let { stateManager.captureSelection(it) }
            // Taken before clearing, so populate can keep the nodes still listed (and so their expansion).
            val previous = node.children().toList().filterIsInstance<CircleCITreeNode>()
            node.removeAllChildren()
            result.fold(
                onSuccess = {
                    populate(it, previous)
                    node.childrenLoaded = true
                },
                onFailure = { error ->
                    logger.error("Failed to load $what: ${error.message}", error)
                    node.add(ErrorNode(error.message ?: "Failed to load $what"))
                    node.childrenLoaded = false
                },
            )
            nodeStructureChanged(node)
            // The nodes a root reload rebuilt are first loaded here, so put
            // back the expansion they had before it. A refresh only keeps
            // what's expanded now, so a node collapsed since stays collapsed.
            tree?.let {
                stateManager.restoreState(
                    it,
                    node,
                    if (refresh) expanded else expanded + expandedBeforeReload,
                )
            }
            restoreSelection(node, selected)

            if (refresh) {
                refreshLoadedChildren(node)
            }
        }
    }

    /**
     * After a refresh kept some loaded children: re-fetch those still
     * expanded, and drop the rest so they load fresh when next expanded.
     */
    private fun refreshLoadedChildren(node: CircleCITreeNode) {
        val tree = tree
        for (child in node.children().toList().filterIsInstance<CircleCITreeNode>()) {
            if (!child.childrenLoaded) continue
            if (tree != null && tree.isExpanded(TreePath(child.path))) {
                loadChildren(child, refresh = true)
            } else {
                child.childrenLoaded = false
                child.removeAllChildren()
                nodeStructureChanged(child)
            }
        }
    }

    /**
     * Reselect what was selected before this node's children were replaced,
     * or else what was selected before the last root reload, once it loads.
     */
    private fun restoreSelection(
        node: CircleCITreeNode,
        selected: String? = null,
    ) {
        val tree = tree ?: return
        stateManager.restoreSelection(tree, node, selected ?: selectedBeforeReload)
        // Once something's selected (that, or whatever the user picked since), stop waiting for it.
        if (tree.selectionPath != null) selectedBeforeReload = null
    }

    private inline fun <reified N : CircleCITreeNode, K> existingChildren(
        previous: List<CircleCITreeNode>,
        key: (N) -> K,
    ): Map<K, N> = previous.filterIsInstance<N>().associateBy(key)

    private fun captureExpansion(): Set<String> {
        return tree?.let { stateManager.captureState(it) } ?: emptySet()
    }

    private fun emptyRunsMessage(): String {
        val filters = stateStore.filters.value
        if (filters.status != null || filters.created != null) {
            return "No runs match the current filters"
        }
        val selected = projectService.getSelectedProject()
        if (!isMyRuns() && selected != null) {
            val scope = runListService.branchScope(selected)
            if (scope is RunListService.BranchScope.Branch) return "No runs on ${scope.name}"
        }
        return "No runs found"
    }

    private suspend fun <T> authenticated(fetch: suspend () -> Result<T>): Result<T> {
        val initialized = withContext(Dispatchers.IO) { ensureApiInitialized() }
        if (!initialized) {
            return Result.failure(IllegalStateException("Not authenticated. Please configure API token in Settings."))
        }
        return try {
            fetch()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Ensure API service is initialized before making calls.
     */
    private fun ensureApiInitialized(): Boolean {
        if (!authService.isAuthenticated()) {
            logger.warn("Cannot load data: not authenticated")
            return false
        }

        val token = authService.getToken()
        if (token == null) {
            logger.warn("Cannot load data: no token available")
            return false
        }

        apiService.initialize(token, settings.hostUrl)
        return true
    }
}
