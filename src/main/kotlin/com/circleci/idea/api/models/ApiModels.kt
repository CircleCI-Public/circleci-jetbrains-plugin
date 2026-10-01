package com.circleci.idea.api.models

import com.google.gson.annotations.SerializedName

/**
 * User information response from /api/v2/me
 */
data class UserInfo(
    @SerializedName("id")
    val id: String,
    @SerializedName("login")
    val login: String,
    @SerializedName("name")
    val name: String?,
)

/**
 * Project information from CircleCI API.
 */
data class ProjectInfo(
    @SerializedName("slug")
    val slug: String? = null,
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("organization_name")
    val organizationName: String? = null,
    @SerializedName("vcs_info")
    val vcsInfo: ProjectVcsInfo? = null,
    @SerializedName("vcs_url")
    val vcsUrl: String? = null,
    @SerializedName("vcs_type")
    val vcsType: String? = null,
    @SerializedName("reponame")
    val reponame: String? = null,
    @SerializedName("username")
    val username: String? = null,
    @SerializedName("default_branch")
    val defaultBranch: String? = null,
    @SerializedName("followed")
    val followed: Boolean = false,
)

data class ProjectVcsInfo(
    @SerializedName("vcs_url")
    val vcsUrl: String?,
    @SerializedName("provider")
    val provider: String?,
    @SerializedName("default_branch")
    val defaultBranch: String?,
)

/**
 * Request body for config validation.
 */
data class ConfigValidationRequest(
    @SerializedName("config")
    val config: String,
)

/**
 * Config validation response.
 */
data class ConfigValidationResponse(
    @SerializedName("valid")
    val valid: Boolean,
    @SerializedName("errors")
    val errors: List<ConfigError> = emptyList(),
    @SerializedName("source_yaml")
    val sourceYaml: String? = null,
    @SerializedName("output_yaml")
    val outputYaml: String? = null,
)

data class ConfigError(
    @SerializedName("type")
    val type: String,
    @SerializedName("message")
    val message: String,
)

/**
 * Artifacts response
 */
data class ArtifactsResponse(
    @SerializedName("items")
    val items: List<ArtifactInfo>?,
    @SerializedName("next_page_token")
    val nextPageToken: String?,
)

/**
 * Individual artifact information
 */
data class ArtifactInfo(
    @SerializedName("path")
    val path: String?,
    @SerializedName("node_index")
    val nodeIndex: Int?,
    @SerializedName("url")
    val url: String?,
    @SerializedName("pretty_path")
    val prettyPath: String?,
)
