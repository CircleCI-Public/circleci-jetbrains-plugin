package com.circleci.idea.api.clients

import com.circleci.idea.api.CircleCIApiClient
import com.circleci.idea.api.models.ConfigCompileAttributes
import com.circleci.idea.api.models.ConfigCompileData
import com.circleci.idea.api.models.ConfigCompileReferences
import com.circleci.idea.api.models.ConfigCompileRequest
import com.circleci.idea.api.models.ConfigCompileResponse
import com.circleci.idea.api.models.ConfigValidationResult
import com.circleci.idea.api.models.V3Ref

/**
 * API client for CircleCI configuration operations.
 * Handles config validation and related operations.
 */
class ConfigApiClient : CircleCIApiClientBase() {
    /**
     * Validate CircleCI configuration YAML by compiling it, via
     * POST /api/v3/configs/compile, as `circleci config validate` does.
     *
     * @param client The initialized API client
     * @param configYaml Configuration YAML content to validate
     * @param branch Branch name, for `<< pipeline.git.branch >>`
     * @param orgId The org to resolve private orbs in; null for public orbs only
     * @return Whether it's valid, with its errors or compiled config
     */
    suspend fun validateConfig(
        client: CircleCIApiClient,
        configYaml: String,
        branch: String,
        orgId: String?,
    ): Result<ConfigValidationResult> {
        val attributes = ConfigCompileAttributes(configYaml, mapOf("pipeline.git.branch" to branch))
        val references = orgId?.let { ConfigCompileReferences(V3Ref(it)) }
        val body = ConfigCompileRequest(ConfigCompileData(attributes, references))

        return executeRequest(client, "/api/v3/configs/compile", body = body) { data ->
            ConfigValidationResult.from(gson.fromJson(data, ConfigCompileResponse::class.java))
        }
    }
}
