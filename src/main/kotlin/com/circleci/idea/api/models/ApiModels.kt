package com.circleci.idea.api.models

import com.google.gson.annotations.SerializedName

/**
 * Generic paginated response from CircleCI API.
 */
data class PaginatedResponse<T>(
    @SerializedName("items")
    val items: List<T> = emptyList(),

    @SerializedName("next_page_token")
    val nextPageToken: String? = null
)

/**
 * User information response from /api/v2/me
 */
data class UserInfo(
    @SerializedName("id")
    val id: String,

    @SerializedName("login")
    val login: String,

    @SerializedName("name")
    val name: String?
)

/**
 * Pipeline information from CircleCI API.
 */
data class PipelineInfo(
    @SerializedName("id")
    val id: String,

    @SerializedName("number")
    val number: Int,

    @SerializedName("project_slug")
    val projectSlug: String,

    @SerializedName("state")
    val state: String,

    @SerializedName("created_at")
    val createdAt: String,

    @SerializedName("updated_at")
    val updatedAt: String? = null,

    @SerializedName("trigger")
    val trigger: TriggerInfo? = null,

    @SerializedName("vcs")
    val vcs: VcsInfo? = null,

    @SerializedName("errors")
    val errors: List<PipelineError> = emptyList()
)

data class TriggerInfo(
    @SerializedName("type")
    val type: String?,

    @SerializedName("received_at")
    val receivedAt: String?,

    @SerializedName("actor")
    val actor: ActorInfo?
)

data class ActorInfo(
    @SerializedName("login")
    val login: String?,

    @SerializedName("avatar_url")
    val avatarUrl: String?
)

data class VcsInfo(
    @SerializedName("branch")
    val branch: String?,

    @SerializedName("revision")
    val revision: String?,

    @SerializedName("tag")
    val tag: String?,

    @SerializedName("commit")
    val commit: CommitInfo?,

    @SerializedName("provider_name")
    val providerName: String?,

    @SerializedName("origin_repository_url")
    val originRepositoryUrl: String?,

    @SerializedName("target_repository_url")
    val targetRepositoryUrl: String?
)

data class CommitInfo(
    @SerializedName("subject")
    val subject: String?,

    @SerializedName("body")
    val body: String?
)

data class PipelineError(
    @SerializedName("type")
    val type: String,

    @SerializedName("message")
    val message: String
)

/**
 * Workflow information from CircleCI API.
 */
data class WorkflowInfo(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("project_slug")
    val projectSlug: String,

    @SerializedName("pipeline_id")
    val pipelineId: String,

    @SerializedName("pipeline_number")
    val pipelineNumber: Int,

    @SerializedName("status")
    val status: String,

    @SerializedName("started_by")
    val startedBy: String?,

    @SerializedName("created_at")
    val createdAt: String,

    @SerializedName("stopped_at")
    val stoppedAt: String?
)

/**
 * Job information from CircleCI API.
 */
data class JobInfo(
    @SerializedName("id")
    val id: String,

    @SerializedName("job_number")
    val jobNumber: Long?,

    @SerializedName("name")
    val name: String,

    @SerializedName("project_slug")
    val projectSlug: String,

    @SerializedName("status")
    val status: String,

    @SerializedName("type")
    val type: String,

    @SerializedName("started_at")
    val startedAt: String?,

    @SerializedName("stopped_at")
    val stoppedAt: String?,

    @SerializedName("dependencies")
    val dependencies: List<String> = emptyList(),

    @SerializedName("approved_by")
    val approvedBy: String?
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
    val followed: Boolean = false
)

data class ProjectVcsInfo(
    @SerializedName("vcs_url")
    val vcsUrl: String?,

    @SerializedName("provider")
    val provider: String?,

    @SerializedName("default_branch")
    val defaultBranch: String?
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
    val outputYaml: String? = null
)

data class ConfigError(
    @SerializedName("type")
    val type: String,

    @SerializedName("message")
    val message: String
)

/**
 * Trigger pipeline response.
 */
data class TriggerPipelineResponse(
    @SerializedName("id")
    val id: String,

    @SerializedName("number")
    val number: Int,

    @SerializedName("state")
    val state: String,

    @SerializedName("created_at")
    val createdAt: String
)
