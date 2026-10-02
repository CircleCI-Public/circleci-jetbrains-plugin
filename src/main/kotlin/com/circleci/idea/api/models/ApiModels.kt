package com.circleci.idea.api.models

import com.google.gson.annotations.SerializedName

/** The signed-in user. */
data class UserInfo(
    val id: String,
    val login: String,
    val name: String?,
    val avatarUrl: String? = null,
)

/** A user from GET /api/v3/users. */
data class UserWire(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("attributes")
    val attributes: UserAttributesWire? = null,
)

data class UserAttributesWire(
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("login")
    val login: String? = null,
    @SerializedName("avatar_url")
    val avatarUrl: String? = null,
)

/**
 * Body for POST /api/v3/configs/compile. The endpoint rejects unknown
 * members, so nothing outside this shape may be sent.
 */
data class ConfigCompileRequest(
    @SerializedName("data")
    val data: ConfigCompileData,
)

data class ConfigCompileData(
    @SerializedName("attributes")
    val attributes: ConfigCompileAttributes,
    @SerializedName("references")
    val references: ConfigCompileReferences? = null,
)

data class ConfigCompileAttributes(
    @SerializedName("config")
    val config: String,
    @SerializedName("pipeline_values")
    val pipelineValues: Map<String, Any>? = null,
)

/** The org to resolve private orbs in. */
data class ConfigCompileReferences(
    @SerializedName("org")
    val org: V3Ref,
)

/**
 * POST /api/v3/configs/compile. A config that fails to compile is still
 * HTTP 200: [ConfigCompileResultAttributes.outcome] is "failed" and the
 * reasons are in [ConfigCompileMeta.messages].
 */
data class ConfigCompileResponse(
    @SerializedName("data")
    val data: ConfigCompileResultWire? = null,
    @SerializedName("meta")
    val meta: ConfigCompileMeta? = null,
)

data class ConfigCompileResultWire(
    @SerializedName("attributes")
    val attributes: ConfigCompileResultAttributes? = null,
)

data class ConfigCompileResultAttributes(
    @SerializedName("outcome")
    val outcome: String? = null,
    @SerializedName("compiled_config")
    val compiledConfig: String? = null,
)

data class ConfigCompileMeta(
    @SerializedName("messages")
    val messages: List<ConfigCompileMessage>? = null,
)

data class ConfigCompileMessage(
    @SerializedName("title")
    val title: String? = null,
)

/** Whether a config compiled, why not, and what it expanded to. */
data class ConfigValidationResult(
    val valid: Boolean,
    val errors: List<String> = emptyList(),
    val compiledConfig: String? = null,
) {
    companion object {
        fun from(response: ConfigCompileResponse): ConfigValidationResult {
            val valid = response.data?.attributes?.outcome == "succeeded"
            return ConfigValidationResult(
                valid = valid,
                errors = response.meta?.messages.orEmpty().mapNotNull { it.title?.takeIf(String::isNotBlank) },
                compiledConfig = response.data?.attributes?.compiledConfig.takeIf { valid },
            )
        }
    }
}
