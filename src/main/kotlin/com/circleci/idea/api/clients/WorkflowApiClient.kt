package com.circleci.idea.api.clients

import com.circleci.idea.api.CircleCIApiClient

/**
 * API client for workflow-related operations.
 * Handles workflow actions (rerun, cancel, approve).
 */
class WorkflowApiClient : CircleCIApiClientBase() {
    /**
     * Rerun a workflow, via POST /api/v3/workflows/{id}/rerun. This creates
     * a new workflow; the old one is unchanged.
     *
     * The fields are V3's `is_from_failed` and `is_ssh_enabled`, not V2's
     * `from_failed` and `enable_ssh`: V3 ignores fields it doesn't know, so
     * the V2 names silently rerun everything with SSH off.
     *
     * @param client The initialized API client
     * @param workflowId Workflow ID
     * @param fromFailed Whether to rerun only failed jobs (and their dependents)
     * @param enableSsh Whether to enable SSH for debugging
     * @param jobs Optional IDs of specific jobs to rerun
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
                put("is_from_failed", fromFailed)
                put("is_ssh_enabled", enableSsh)
                if (jobs != null) {
                    put("jobs", jobs)
                }
            }

        return executeRequest(client, "/api/v3/workflows/$workflowId/rerun", body = body) { }
    }

    /**
     * Cancel a workflow, via POST /api/v3/workflows/{id}/cancel. Cancellation
     * happens asynchronously.
     *
     * @param client The initialized API client
     * @param workflowId Workflow ID
     * @return Success or error
     */
    fun cancelWorkflow(
        client: CircleCIApiClient,
        workflowId: String,
    ): Result<Unit> {
        return executePostRequest(client, "/api/v3/workflows/$workflowId/cancel")
    }

    /**
     * Approve a workflow's on-hold job. V3 has no approval endpoint yet, so this stays on V2.
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
}
