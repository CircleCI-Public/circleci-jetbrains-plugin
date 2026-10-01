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
 * Detailed job information from CircleCI API.
 */
data class JobDetailsInfo(
    @SerializedName("id")
    val id: String?,
    @SerializedName("job_number")
    val jobNumber: Long?,
    @SerializedName("name")
    val name: String?,
    @SerializedName("project_slug")
    val projectSlug: String?,
    @SerializedName("status")
    val status: String?,
    @SerializedName("type")
    val type: String?,
    @SerializedName("start_time")
    val startedAt: String?,
    @SerializedName("stop_time")
    val stoppedAt: String?,
    @SerializedName("duration")
    val duration: Long?,
    @SerializedName("executor")
    val executor: ExecutorInfo? = null,
    @SerializedName("parallelism")
    val parallelism: Int? = null,
    @SerializedName("steps")
    val steps: List<JobStepInfo>? = null,
    @SerializedName("ssh")
    val ssh: SshInfo? = null,
    @SerializedName("web_url")
    val webUrl: String?,
)

data class ExecutorInfo(
    @SerializedName("type")
    val type: String?,
    @SerializedName("resource_class")
    val resourceClass: String?,
)

data class JobStepInfo(
    @SerializedName("name")
    val name: String?,
    @SerializedName("actions")
    val actions: List<JobActionInfo>? = null,
)

data class JobActionInfo(
    @SerializedName("name")
    val name: String?,
    @SerializedName("status")
    val status: String?,
    @SerializedName("start_time")
    val startTime: String?,
    @SerializedName("end_time")
    val endTime: String?,
    @SerializedName("run_time_millis")
    val runTimeMillis: Long?,
    @SerializedName("output_url")
    val outputUrl: String?,
    @SerializedName("step")
    val step: Int?,
    @SerializedName("index")
    val index: Int?,
)

data class SshInfo(
    @SerializedName("enabled")
    val enabled: Boolean = false,
    @SerializedName("host")
    val host: String? = null,
    @SerializedName("port")
    val port: Int? = null,
    @SerializedName("user")
    val user: String? = null,
)

/**
 * Test results response
 */
data class TestResultsResponse(
    @SerializedName("items")
    val items: List<TestInfo>?,
    @SerializedName("next_page_token")
    val nextPageToken: String?,
)

/**
 * Individual test result information
 */
data class TestInfo(
    @SerializedName("name")
    val name: String?,
    @SerializedName("classname")
    val classname: String?,
    @SerializedName("file")
    val file: String?,
    // "success", "failure", "skipped"
    @SerializedName("result")
    val result: String?,
    @SerializedName("message")
    val message: String?,
    @SerializedName("source")
    val source: String?,
    @SerializedName("run_time")
    val runTime: Double?,
    @SerializedName("flaky")
    val flaky: Boolean?,
)

/**
 * Step output response (from output_url)
 */
data class StepOutputResponse(
    @SerializedName("message")
    val message: String?,
    @SerializedName("type")
    val type: String?,
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
