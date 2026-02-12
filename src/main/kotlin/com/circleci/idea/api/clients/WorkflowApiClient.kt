package com.circleci.idea.api.clients

import com.circleci.idea.api.CircleCIApiClient
import com.circleci.idea.api.models.PaginatedResponse
import com.circleci.idea.api.models.WorkflowInfo
import com.google.gson.reflect.TypeToken

/**
 * API client for workflow-related operations.
 * Handles fetching workflows and workflow actions (rerun, cancel, approve).
 */
class WorkflowApiClient : CircleCIApiClientBase() {
    /**
     * Get workflows for a pipeline.
     *
     * @param client The initialized API client
     * @param pipelineId Pipeline ID
     * @return Paginated list of workflows
     */
    fun getWorkflows(
        client: CircleCIApiClient,
        pipelineId: String,
    ): Result<PaginatedResponse<WorkflowInfo>> {
        return executeRequest(client, "/api/v2/pipeline/$pipelineId/workflow") { data ->
            gson.fromJson(data.toString(), object : TypeToken<PaginatedResponse<WorkflowInfo>>() {}.type)
        }
    }

    /**
     * Rerun a workflow.
     *
     * @param client The initialized API client
     * @param workflowId Workflow ID
     * @param fromFailed Whether to rerun only failed jobs
     * @param enableSsh Whether to enable SSH for debugging
     * @param jobs Optional list of specific jobs to rerun
     * @return Success or error
     */
    fun rerunWorkflow(
        client: CircleCIApiClient,
        workflowId: String,
        fromFailed: Boolean = false,
        enableSsh: Boolean = false,
        jobs: List<String>? = null,
    ): Result<Unit> {
        val body =
            buildMap {
                put("from_failed", fromFailed)
                put("enable_ssh", enableSsh)
                if (jobs != null) {
                    put("jobs", jobs)
                }
            }

        return executeRequest(client, "/api/v2/workflow/$workflowId/rerun", emptyMap(), body) { Unit }
    }

    /**
     * Cancel a workflow.
     *
     * @param client The initialized API client
     * @param workflowId Workflow ID
     * @return Success or error
     */
    fun cancelWorkflow(
        client: CircleCIApiClient,
        workflowId: String,
    ): Result<Unit> {
        return executePostRequest(client, "/api/v2/workflow/$workflowId/cancel")
    }

    /**
     * Approve a workflow.
     *
     * @param client The initialized API client
     * @param workflowId Workflow ID
     * @param approvalRequestId Approval request ID
     * @return Success or error
     */
    fun approveWorkflow(
        client: CircleCIApiClient,
        workflowId: String,
        approvalRequestId: String,
    ): Result<Unit> {
        return executePostRequest(client, "/api/v2/workflow/$workflowId/approve/$approvalRequestId")
    }

    /**
     * Rerun a job with SSH enabled.
     * This reruns the entire workflow with SSH enabled for debugging.
     *
     * @param client The initialized API client
     * @param workflowId Workflow ID
     * @param jobId Job ID (currently unused by API, but kept for signature compatibility)
     * @return Success or error
     */
    fun rerunJobWithSsh(
        client: CircleCIApiClient,
        workflowId: String,
        @Suppress("UNUSED_PARAMETER") jobId: String,
    ): Result<Unit> {
        return executePostRequest(client, "/api/v2/workflow/$workflowId/rerun")
    }
}
