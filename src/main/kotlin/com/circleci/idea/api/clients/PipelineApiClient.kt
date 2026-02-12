package com.circleci.idea.api.clients

import com.circleci.idea.api.CircleCIApiClient
import com.circleci.idea.api.models.PaginatedResponse
import com.circleci.idea.api.models.PipelineInfo
import com.circleci.idea.api.models.TriggerPipelineResponse
import com.google.gson.reflect.TypeToken

/**
 * API client for pipeline-related operations.
 * Handles fetching and triggering pipelines.
 */
class PipelineApiClient : CircleCIApiClientBase() {
    /**
     * Get pipelines for a project.
     *
     * @param client The initialized API client
     * @param projectSlug Project slug (e.g., "gh/username/repo")
     * @param branch Optional branch filter
     * @param pageToken Optional pagination token
     * @return Paginated list of pipelines
     */
    fun getPipelines(
        client: CircleCIApiClient,
        projectSlug: String,
        branch: String? = null,
        pageToken: String? = null,
    ): Result<PaginatedResponse<PipelineInfo>> {
        val params = mutableMapOf<String, String>()
        if (branch != null) params["branch"] = branch
        if (pageToken != null) params["page-token"] = pageToken

        return executeRequest(client, "/api/v2/project/$projectSlug/pipeline", params) { data ->
            gson.fromJson(data.toString(), object : TypeToken<PaginatedResponse<PipelineInfo>>() {}.type)
        }
    }

    /**
     * Trigger a pipeline with custom config.
     *
     * @param client The initialized API client
     * @param projectSlug Project slug
     * @param branch Branch to trigger on
     * @param configYaml Optional custom config YAML
     * @param parameters Optional pipeline parameters
     * @return Pipeline trigger response
     */
    fun triggerPipeline(
        client: CircleCIApiClient,
        projectSlug: String,
        branch: String,
        configYaml: String? = null,
        parameters: Map<String, Any> = emptyMap(),
    ): Result<TriggerPipelineResponse> {
        val body =
            mutableMapOf<String, Any>(
                "branch" to branch,
                "parameters" to parameters,
            )
        if (configYaml != null) {
            body["config_yaml"] = configYaml
        }

        return executeRequest(client, "/api/v2/project/$projectSlug/pipeline", emptyMap(), body) { data ->
            gson.fromJson(data.toString(), TriggerPipelineResponse::class.java)
        }
    }
}
