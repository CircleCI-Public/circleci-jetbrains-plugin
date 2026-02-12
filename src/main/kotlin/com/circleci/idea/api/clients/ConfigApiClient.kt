package com.circleci.idea.api.clients

import com.circleci.idea.api.CircleCIApiClient
import com.circleci.idea.api.models.ConfigValidationResponse

/**
 * API client for CircleCI configuration operations.
 * Handles config validation and related operations.
 */
class ConfigApiClient : CircleCIApiClientBase() {
    /**
     * Validate CircleCI configuration YAML.
     *
     * @param client The initialized API client
     * @param configYaml Configuration YAML content to validate
     * @param projectSlug Project slug for context
     * @param branch Branch name for context
     * @return Validation response with errors or compiled config
     */
    fun validateConfig(
        client: CircleCIApiClient,
        configYaml: String,
        projectSlug: String,
        branch: String,
    ): Result<ConfigValidationResponse> {
        val body =
            mapOf(
                "config_yaml" to configYaml,
                "pipeline_values" to
                    mapOf(
                        "branch" to branch,
                        "project_slug" to projectSlug,
                    ),
            )

        return executeRequest(client, "/api/v2/compile-config-with-defaults", emptyMap(), body) { data ->
            gson.fromJson(data.toString(), ConfigValidationResponse::class.java)
        }
    }
}
