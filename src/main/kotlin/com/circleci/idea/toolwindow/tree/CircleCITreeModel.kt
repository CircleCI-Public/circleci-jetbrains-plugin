package com.circleci.idea.toolwindow.tree

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.filter.PipelineFilterService
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.project.CircleCIProjectService
import com.circleci.idea.settings.CircleCISettings
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultTreeModel

/**
 * Tree model for the CircleCI tree view.
 * Handles async loading of data and tree structure updates.
 */
class CircleCITreeModel(
    private val project: Project,
    private val scope: CoroutineScope
) : DefaultTreeModel(RootNode()) {

    private val logger = CircleCILogger.getInstance()
    private val projectService = project.getService(CircleCIProjectService::class.java)
    private val apiService = CircleCIApiService.getInstance()
    private val authService = CircleCIAuthService.getInstance(project)
    private val settings = CircleCISettings.getInstance()
    private val filterService = PipelineFilterService.getInstance(project)

    init {
        // Listen to project changes and reload root
        scope.launch {
            projectService.selectedProjects.collect { selectedSlugs ->
                logger.info("StateFlow: selectedProjects changed, size=${selectedSlugs.size}, slugs=$selectedSlugs")
                withContext(Dispatchers.Main) {
                    reloadRoot()
                }
            }
        }

        // Also listen to projects being populated (in case selection was persisted before detection)
        scope.launch {
            projectService.projects.collect { allProjects ->
                logger.info("StateFlow: projects changed, size=${allProjects.size}")
                withContext(Dispatchers.Main) {
                    reloadRoot()
                }
            }
        }
    }

    /**
     * Reload the root node with current projects.
     */
    fun reloadRoot() {
        SwingUtilities.invokeLater {
            val rootNode = root as RootNode
            rootNode.removeAllChildren()

            val selectedProjects = projectService.getSelectedProjectObjects()
            logger.info("Reloading root with ${selectedProjects.size} selected projects")

            if (selectedProjects.isNotEmpty()) {
                selectedProjects.forEach { circleCIProject ->
                    logger.info("Adding project to tree: ${circleCIProject.slug}")
                    val projectNode = ProjectNode(circleCIProject)
                    rootNode.add(projectNode)
                }
                reload(rootNode)
                logger.info("Tree reloaded - root child count: ${rootNode.childCount}, root: $rootNode")
                logger.info("Tree structure: ${dumpTree(rootNode, 0)}")
            } else {
                logger.info("No projects selected, showing empty state")
                val emptyNode = EmptyNode("No CircleCI project selected")
                rootNode.add(emptyNode)
                reload(rootNode)
                logger.info("Empty state loaded - root child count: ${rootNode.childCount}")
            }
        }
    }

    private fun dumpTree(node: javax.swing.tree.TreeNode, depth: Int): String {
        val builder = StringBuilder()
        builder.append("  ".repeat(depth))
        builder.append("- ${node::class.simpleName}: ${node}\n")
        for (i in 0 until node.childCount) {
            builder.append(dumpTree(node.getChildAt(i), depth + 1))
        }
        return builder.toString()
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
        if (node.childrenLoaded) {
            return
        }

        when (node) {
            is ProjectNode -> loadPipelinesForProject(node)
            is PipelineNode -> loadWorkflowsForPipeline(node)
            is WorkflowNode -> loadJobsForWorkflow(node)
            else -> {}
        }
    }

    /**
     * Load more items for pagination.
     */
    fun loadMore(loadMoreNode: LoadMoreNode) {
        val parent = loadMoreNode.parent as? CircleCITreeNode ?: return

        when (parent) {
            is ProjectNode -> loadMorePipelines(parent, loadMoreNode)
            is PipelineNode -> loadMoreWorkflows(parent, loadMoreNode)
            is WorkflowNode -> loadMoreJobs(parent, loadMoreNode)
            else -> {}
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

    private fun loadPipelinesForProject(projectNode: ProjectNode) {
        // Add loading indicator
        SwingUtilities.invokeLater {
            projectNode.removeAllChildren()
            projectNode.add(LoadingNode())
            nodeStructureChanged(projectNode)
        }

        scope.launch {
            try {
                // Ensure API is initialized
                if (!ensureApiInitialized()) {
                    withContext(Dispatchers.Main) {
                        projectNode.removeAllChildren()
                        projectNode.add(ErrorNode("Not authenticated. Please configure API token in Settings."))
                        nodeStructureChanged(projectNode)
                    }
                    return@launch
                }

                logger.info("Loading pipelines for project: ${projectNode.project.slug}")

                // Get branch filter
                val branchFilter = filterService.getBranchForFilter(projectNode.project.slug)
                logger.info("Using branch filter: $branchFilter")

                val result = apiService.getPipelines(
                    projectSlug = projectNode.project.slug,
                    branch = branchFilter,
                    pageToken = null
                )

                result.fold(
                    onSuccess = { response ->
                        logger.info("Successfully loaded ${response.items.size} pipelines for ${projectNode.project.slug}")

                        // Get current user login for filtering
                        val currentUserLogin = getUserLoginForFiltering()

                        // Apply client-side filters
                        val filteredPipelines = filterService.filterPipelines(response.items, currentUserLogin)
                        logger.info("Filtered to ${filteredPipelines.size} pipelines")

                        withContext(Dispatchers.Main) {
                            projectNode.removeAllChildren()

                            if (filteredPipelines.isEmpty()) {
                                val message = if (filterService.hasActiveFilters()) {
                                    "No pipelines match current filters"
                                } else {
                                    "No pipelines found"
                                }
                                projectNode.add(EmptyNode(message))
                            } else {
                                filteredPipelines.forEach { pipeline ->
                                    projectNode.add(PipelineNode(pipeline))
                                }

                                // Add "Load More" if there's a next page
                                if (response.nextPageToken != null) {
                                    projectNode.add(LoadMoreNode("pipelines", response.nextPageToken))
                                }
                            }
                            projectNode.childrenLoaded = true
                            nodeStructureChanged(projectNode)
                        }
                    },
                    onFailure = { error ->
                        logger.error("Failed to load pipelines for ${projectNode.project.slug}: ${error.message}", error)
                        withContext(Dispatchers.Main) {
                            projectNode.removeAllChildren()
                            projectNode.add(ErrorNode(error.message ?: "Failed to load pipelines"))
                            nodeStructureChanged(projectNode)
                        }
                    }
                )
            } catch (e: Exception) {
                logger.error("Exception loading pipelines for ${projectNode.project.slug}", e)
                withContext(Dispatchers.Main) {
                    projectNode.removeAllChildren()
                    projectNode.add(ErrorNode(e.message ?: "Failed to load pipelines"))
                    nodeStructureChanged(projectNode)
                }
            }
        }
    }

    private fun loadMorePipelines(projectNode: ProjectNode, loadMoreNode: LoadMoreNode) {
        // Replace "Load More" with loading indicator
        SwingUtilities.invokeLater {
            val index = projectNode.getIndex(loadMoreNode)
            projectNode.remove(loadMoreNode)
            projectNode.insert(LoadingNode(), index)
            nodeStructureChanged(projectNode)
        }

        scope.launch {
            try {
                // Get branch filter
                val branchFilter = filterService.getBranchForFilter(projectNode.project.slug)

                val result = apiService.getPipelines(
                    projectSlug = projectNode.project.slug,
                    branch = branchFilter,
                    pageToken = loadMoreNode.nextPageToken
                )

                result.fold(
                    onSuccess = { response ->
                        // Get current user login for filtering
                        val currentUserLogin = getUserLoginForFiltering()

                        // Apply client-side filters
                        val filteredPipelines = filterService.filterPipelines(response.items, currentUserLogin)

                        withContext(Dispatchers.Main) {
                            // Remove loading indicator
                            projectNode.children().toList().filterIsInstance<LoadingNode>().forEach {
                                projectNode.remove(it)
                            }

                            filteredPipelines.forEach { pipeline ->
                                projectNode.add(PipelineNode(pipeline))
                            }

                            // Add new "Load More" if there's another page
                            if (response.nextPageToken != null) {
                                projectNode.add(LoadMoreNode("pipelines", response.nextPageToken))
                            }

                            nodeStructureChanged(projectNode)
                        }
                    },
                    onFailure = { error ->
                        logger.error("Failed to load more pipelines: ${error.message}", error)
                        withContext(Dispatchers.Main) {
                            projectNode.children().toList().filterIsInstance<LoadingNode>().forEach {
                                projectNode.remove(it)
                            }
                            nodeStructureChanged(projectNode)
                        }
                    }
                )
            } catch (e: Exception) {
                logger.error("Failed to load more pipelines", e)
                withContext(Dispatchers.Main) {
                    projectNode.children().toList().filterIsInstance<LoadingNode>().forEach {
                        projectNode.remove(it)
                    }
                    nodeStructureChanged(projectNode)
                }
            }
        }
    }

    private fun loadWorkflowsForPipeline(pipelineNode: PipelineNode) {
        SwingUtilities.invokeLater {
            pipelineNode.removeAllChildren()
            pipelineNode.add(LoadingNode())
            nodeStructureChanged(pipelineNode)
        }

        scope.launch {
            try {
                // Ensure API is initialized
                if (!ensureApiInitialized()) {
                    withContext(Dispatchers.Main) {
                        pipelineNode.removeAllChildren()
                        pipelineNode.add(ErrorNode("Not authenticated. Please configure API token in Settings."))
                        nodeStructureChanged(pipelineNode)
                    }
                    return@launch
                }

                logger.info("Loading workflows for pipeline: ${pipelineNode.pipeline.id}")
                val result = apiService.getWorkflows(pipelineNode.pipeline.id)

                result.fold(
                    onSuccess = { response ->
                        logger.info("Successfully loaded ${response.items.size} workflows")
                        withContext(Dispatchers.Main) {
                            pipelineNode.removeAllChildren()

                            if (response.items.isEmpty()) {
                                pipelineNode.add(EmptyNode("No workflows found"))
                            } else {
                                response.items.forEach { workflow ->
                                    pipelineNode.add(WorkflowNode(workflow))
                                }
                            }
                            pipelineNode.childrenLoaded = true
                            nodeStructureChanged(pipelineNode)
                        }
                    },
                    onFailure = { error ->
                        logger.error("Failed to load workflows: ${error.message}", error)
                        withContext(Dispatchers.Main) {
                            pipelineNode.removeAllChildren()
                            pipelineNode.add(ErrorNode(error.message ?: "Failed to load workflows"))
                            nodeStructureChanged(pipelineNode)
                        }
                    }
                )
            } catch (e: Exception) {
                logger.error("Exception loading workflows", e)
                withContext(Dispatchers.Main) {
                    pipelineNode.removeAllChildren()
                    pipelineNode.add(ErrorNode(e.message ?: "Failed to load workflows"))
                    nodeStructureChanged(pipelineNode)
                }
            }
        }
    }

    private fun loadMoreWorkflows(pipelineNode: PipelineNode, loadMoreNode: LoadMoreNode) {
        // Workflows don't support pagination via the API service, so this is a no-op
        logger.debug("Load more workflows not supported")
    }

    private fun loadJobsForWorkflow(workflowNode: WorkflowNode) {
        SwingUtilities.invokeLater {
            workflowNode.removeAllChildren()
            workflowNode.add(LoadingNode())
            nodeStructureChanged(workflowNode)
        }

        scope.launch {
            try {
                // Ensure API is initialized
                if (!ensureApiInitialized()) {
                    withContext(Dispatchers.Main) {
                        workflowNode.removeAllChildren()
                        workflowNode.add(ErrorNode("Not authenticated. Please configure API token in Settings."))
                        nodeStructureChanged(workflowNode)
                    }
                    return@launch
                }

                logger.info("Loading jobs for workflow: ${workflowNode.workflow.id}")
                val result = apiService.getJobs(workflowNode.workflow.id)

                result.fold(
                    onSuccess = { response ->
                        logger.info("Successfully loaded ${response.items.size} jobs")
                        withContext(Dispatchers.Main) {
                            workflowNode.removeAllChildren()

                            if (response.items.isEmpty()) {
                                workflowNode.add(EmptyNode("No jobs found"))
                            } else {
                                response.items.forEach { job ->
                                    workflowNode.add(JobNode(job))
                                }
                            }
                            workflowNode.childrenLoaded = true
                            nodeStructureChanged(workflowNode)
                        }
                    },
                    onFailure = { error ->
                        logger.error("Failed to load jobs: ${error.message}", error)
                        withContext(Dispatchers.Main) {
                            workflowNode.removeAllChildren()
                            workflowNode.add(ErrorNode(error.message ?: "Failed to load jobs"))
                            nodeStructureChanged(workflowNode)
                        }
                    }
                )
            } catch (e: Exception) {
                logger.error("Exception loading jobs", e)
                withContext(Dispatchers.Main) {
                    workflowNode.removeAllChildren()
                    workflowNode.add(ErrorNode(e.message ?: "Failed to load jobs"))
                    nodeStructureChanged(workflowNode)
                }
            }
        }
    }

    private fun loadMoreJobs(workflowNode: WorkflowNode, loadMoreNode: LoadMoreNode) {
        // Jobs don't support pagination via the API service, so this is a no-op
        logger.debug("Load more jobs not supported")
    }

    /**
     * Get current user login for filtering pipelines.
     * Attempts to fetch from API if not available.
     */
    private suspend fun getUserLoginForFiltering(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val userInfo = apiService.getCurrentUser()
                userInfo.getOrNull()?.login
            } catch (e: Exception) {
                logger.warn("Failed to get current user for filtering: ${e.message}")
                null
            }
        }
    }
}
