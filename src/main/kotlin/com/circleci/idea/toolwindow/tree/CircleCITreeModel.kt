package com.circleci.idea.toolwindow.tree

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.tree.TreeState
import javax.swing.SwingUtilities

/**
 * The run tree's nodes: the runs listed (the selected project's, or "My
 * runs"), each run's workflows and each workflow's jobs.
 *
 * Children load asynchronously when a node is first opened. A refresh
 * re-fetches every loaded level in place, keeping the nodes of runs and
 * workflows that are still listed. What's open and selected is kept by key
 * in [treeState], so it carries over to the nodes a reload rebuilds, which
 * load again if they're open.
 *
 * All node mutation happens on the EDT: the coroutine [scope] runs on
 * Dispatchers.Main, and the fetches switch to IO themselves.
 */
class CircleCITreeModel(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private val logger = CircleCILogger.getInstance()
    private val projectService = project.getService(CircleCIProjectService::class.java)
    private val apiService = CircleCIApiService.getInstance()
    private val authService = CircleCIAuthService.getInstance(project)
    private val settings = CircleCISettings.getInstance()
    private val stateStore = CircleCIStateStore.getInstance(project)
    private val runListService = RunListService.getInstance(project)
    private val pollingService = project.getService(RunPollingService::class.java)

    /** The (hidden) root, whose children are the runs listed. */
    val root = RootNode()

    private val _structure = MutableStateFlow(0)

    /** Bumped each time nodes are added, removed or updated, for the view to rebuild its tree. */
    val structure: StateFlow<Int> = _structure.asStateFlow()

    /** How far the tree is scrolled, shared with its scrollbar. */
    val scroll = LazyListState()

    /** What's open and selected in the tree, by [keyOf]. */
    val treeState = TreeState(SelectableLazyListState(scroll))

    // Loads and refreshes in flight, for the loading stripe.
    private var activeLoads = 0
    private val _loading = MutableStateFlow(false)

    /** Whether any of the tree is loading or refreshing. */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

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

        // Load what's opened; forget a failed load on closing, to retry on reopening.
        scope.launch {
            var previous = emptySet<Any>()
            snapshotFlow { treeState.openNodes }.collect { open ->
                forgetFailedLoads(previous - open)
                previous = open
                loadOpened()
            }
        }
    }

    /** The node selected in the tree, if it's still there. */
    fun selectedNode(): CircleCITreeNode? = treeState.selectedKeys.firstOrNull()?.let { findNode(root, it) }

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
            if (runFetcher() != null) {
                loadRuns(refresh = false)
            } else {
                root.loadGeneration++
                root.childrenLoaded = false
                root.removeAllChildren()
                root.add(EmptyNode("No CircleCI project found in this workspace"))
                structureChanged()
            }
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
        structureChanged()

        tracked {
            val result = authenticated { fetch(loadMoreNode.nextCursor) }
            // A refresh or reload replaced the list while this page was loading.
            if (generation != parent.loadGeneration || loadingNode.parent !== parent) return@tracked

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
            structureChanged()
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
        val node = root
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
            // The run list's loading shows as the stripe above it (and the
            // empty tree's text); a run or workflow shows its own.
            if (node !== root) node.add(LoadingNode())
            structureChanged()
        }

        tracked {
            val result = authenticated(fetch)
            // Stale, or for a node no longer in the tree.
            if (generation != node.loadGeneration || (node !== root && node.parent == null)) return@tracked
            if (refresh && result.isFailure) {
                // Keep showing what loaded last; the next refresh tries again.
                logger.warn("Failed to refresh $what: ${result.exceptionOrNull()?.message}")
                return@tracked
            }

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
            if (refresh) {
                refreshLoadedChildren(node)
            }
            structureChanged()
            // Nodes rebuilt while open load again.
            loadOpened()
        }
    }

    /**
     * After a refresh kept some loaded children: re-fetch those still
     * open, and drop the rest so they load fresh when next opened.
     */
    private fun refreshLoadedChildren(node: CircleCITreeNode) {
        val open = treeState.openNodes
        for (child in children(node)) {
            if (!child.childrenLoaded) continue
            if (keyOf(child) in open) {
                loadChildren(child, refresh = true)
            } else {
                child.childrenLoaded = false
                child.removeAllChildren()
            }
        }
    }

    /** Load the children of the open nodes that have none yet. */
    private fun loadOpened() {
        nodesToLoad(root, treeState.openNodes).forEach { loadChildren(it, refresh = false) }
    }

    /** Clear the error (or loading) rows of nodes just closed, so opening them again retries. */
    private fun forgetFailedLoads(closed: Set<Any>) {
        val nodes = closed.mapNotNull { findNode(root, it) }.filter { !it.childrenLoaded && it.childCount > 0 }
        if (nodes.isEmpty()) return
        nodes.forEach { it.removeAllChildren() }
        structureChanged()
    }

    private fun structureChanged() {
        _structure.value++
    }

    /** Launch [block] on the EDT, counted as a load in flight until it ends, however it ends. */
    private fun tracked(block: suspend CoroutineScope.() -> Unit) {
        activeLoads++
        _loading.value = true
        scope.launch {
            try {
                block()
            } finally {
                activeLoads--
                _loading.value = activeLoads > 0
            }
        }
    }

    private inline fun <reified N : CircleCITreeNode, K> existingChildren(
        previous: List<CircleCITreeNode>,
        key: (N) -> K,
    ): Map<K, N> = previous.filterIsInstance<N>().associateBy(key)

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
