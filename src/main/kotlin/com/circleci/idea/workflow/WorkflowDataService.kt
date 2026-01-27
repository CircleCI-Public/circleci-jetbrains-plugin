package com.circleci.idea.workflow

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.api.models.WorkflowInfo
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.state.Workflow
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Service for managing workflow data.
 * Handles fetching and state management for workflows.
 */
@Service(Service.Level.PROJECT)
class WorkflowDataService(private val project: Project) {
    private val logger = CircleCILogger.getInstance()
    private val stateStore = project.getService(CircleCIStateStore::class.java)
    private val apiService = CircleCIApiService.getInstance()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        logger.logLifecycleEvent("WorkflowDataService initialized")
    }

    /**
     * Fetch workflows for a pipeline.
     *
     * @param projectSlug Project slug
     * @param pipelineId Pipeline ID
     * @return List of workflows
     */
    suspend fun fetchWorkflows(
        projectSlug: String,
        pipelineId: String,
    ): List<Workflow> {
        logger.info("Fetching workflows for pipeline $pipelineId")
        _isLoading.value = true

        try {
            val result = apiService.getWorkflows(pipelineId)

            return result.fold(
                onSuccess = { response ->
                    val workflows = response.items.map { convertToWorkflow(it) }

                    // Update state store
                    stateStore.updateWorkflows(projectSlug, pipelineId, workflows)

                    logger.info("Fetched ${workflows.size} workflows for pipeline $pipelineId")
                    workflows
                },
                onFailure = { error ->
                    logger.error("Failed to fetch workflows for pipeline $pipelineId: ${error.message}", error)
                    emptyList()
                },
            )
        } catch (e: Exception) {
            logger.error("Failed to fetch workflows for pipeline $pipelineId", e)
            return emptyList()
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Fetch workflows for multiple pipelines.
     *
     * @param projectSlug Project slug
     * @param pipelineIds List of pipeline IDs
     * @return Map of pipeline ID to workflows
     */
    suspend fun fetchWorkflowsForPipelines(
        projectSlug: String,
        pipelineIds: List<String>,
    ): Map<String, List<Workflow>> {
        logger.info("Fetching workflows for ${pipelineIds.size} pipelines")

        val results = mutableMapOf<String, List<Workflow>>()

        for (pipelineId in pipelineIds) {
            val workflows = fetchWorkflows(projectSlug, pipelineId)
            results[pipelineId] = workflows
        }

        return results
    }

    /**
     * Refresh workflows for a pipeline.
     *
     * @param projectSlug Project slug
     * @param pipelineId Pipeline ID
     */
    suspend fun refreshWorkflows(
        projectSlug: String,
        pipelineId: String,
    ) {
        logger.info("Refreshing workflows for pipeline $pipelineId")
        fetchWorkflows(projectSlug, pipelineId)
    }

    /**
     * Convert API WorkflowInfo to domain Workflow model.
     */
    private fun convertToWorkflow(workflowInfo: WorkflowInfo): Workflow {
        return Workflow(
            id = workflowInfo.id,
            name = workflowInfo.name,
            status = workflowInfo.status,
            createdAt = workflowInfo.createdAt,
            stoppedAt = workflowInfo.stoppedAt,
        )
    }

    /**
     * Check if a workflow needs approval.
     */
    fun needsApproval(workflow: Workflow): Boolean {
        return workflow.status == "on_hold"
    }

    /**
     * Check if a workflow is running.
     */
    fun isRunning(workflow: Workflow): Boolean {
        return workflow.status == "running" || workflow.status == "failing"
    }

    /**
     * Check if a workflow is complete.
     */
    fun isComplete(workflow: Workflow): Boolean {
        return workflow.status in setOf("success", "failed", "canceled", "error")
    }

    /**
     * Check if a workflow failed.
     */
    fun isFailed(workflow: Workflow): Boolean {
        return workflow.status in setOf("failed", "failing", "error")
    }
}
