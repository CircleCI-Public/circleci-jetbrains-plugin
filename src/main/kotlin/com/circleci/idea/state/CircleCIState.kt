package com.circleci.idea.state

import com.circleci.idea.run.CreatedFilter
import com.circleci.idea.run.RunScope
import com.circleci.idea.run.RunStatus
import com.circleci.idea.run.RunStatusFilter
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/**
 * Root state interface for CircleCI plugin.
 * Provides access to all state slices.
 */
interface CircleCIState {
    val auth: StateFlow<AuthState>
    val projects: StateFlow<ProjectsState>
    val projectsData: StateFlow<ProjectsDataState>
    val config: StateFlow<ConfigState>
    val filters: StateFlow<FiltersState>
    val ui: StateFlow<UIState>
}

/**
 * Authentication state.
 */
data class AuthState(
    val isAuthenticated: Boolean = false,
    val token: String? = null,
    val hostUrl: String = "https://circleci.com",
    val user: User? = null,
    val error: String? = null,
)

data class User(
    val id: String,
    val login: String,
    val name: String,
)

/**
 * Projects state - list of selected/followed projects.
 */
data class ProjectsState(
    // Project slugs
    val selectedProjects: List<String> = emptyList(),
    val followedProjects: List<Project> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class Project(
    val slug: String,
    val name: String,
    val organization: String,
    val vcsProvider: String,
    val defaultBranch: String?,
    val followed: Boolean = false,
)

/**
 * Projects data state - the runs (and their workflows and jobs) last loaded for each project.
 */
data class ProjectsDataState(
    // Key: project slug
    val data: Map<String, ProjectData> = emptyMap(),
    val isRefreshing: Boolean = false,
    val lastRefresh: Long? = null,
    val error: String? = null,
)

data class ProjectData(
    val projectSlug: String,
    val runs: List<Run> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val nextCursor: String? = null,
)

data class Run(
    val id: String,
    val number: Long?,
    val projectId: String?,
    // Null when it can't be told from the run alone (a cross-project run from a
    // non-GitHub/Bitbucket provider); resolve it from projectId when needed.
    val projectSlug: String?,
    // "org/repo", for labelling runs from more than one project
    val repositoryName: String?,
    val status: RunStatus,
    val createdAt: Instant?,
    val branch: String?,
    val tag: String?,
    val revision: String?,
    val commitSubject: String?,
    val commitAuthor: String?,
    val triggeredBy: String?,
    val errors: List<RunError> = emptyList(),
    val workflows: List<Workflow> = emptyList(),
)

data class RunError(
    val type: String?,
    val message: String?,
)

data class Workflow(
    val id: String,
    val name: String,
    val status: RunStatus,
    val createdAt: Instant?,
    val endedAt: Instant?,
    val runId: String,
    val runNumber: Long?,
    val projectSlug: String?,
    val jobs: List<Job> = emptyList(),
)

data class Job(
    val id: String,
    val number: Long?,
    val name: String,
    val type: String?,
    val status: RunStatus,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val workflowId: String,
    val projectSlug: String?,
)

/**
 * Configuration state - parsed CircleCI config for workspace.
 */
data class ConfigState(
    val configPath: String? = null,
    val isValid: Boolean? = null,
    val errors: List<ConfigError> = emptyList(),
    val lastValidation: Long? = null,
)

data class ConfigError(
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
)

/**
 * Filters state - user-selected filters for the run list.
 */
data class FiltersState(
    val scope: RunScope = RunScope.CURRENT_BRANCH,
    // Null = all statuses
    val status: RunStatusFilter? = null,
    // Null = all dates
    val created: CreatedFilter? = null,
)

/**
 * UI state - transient UI-specific state.
 */
data class UIState(
    // Item IDs that are expanded in tree
    val expandedItems: Set<String> = emptySet(),
    // Currently selected item ID
    val selectedItem: String? = null,
    val isToolWindowVisible: Boolean = false,
    val notificationPreferences: NotificationPreferences = NotificationPreferences(),
)

data class NotificationPreferences(
    val enabled: Boolean = true,
    val myRunsOnly: Boolean = false,
    val statusFilter: Set<String> = setOf("failed", "failing", "canceled", "on_hold", "error", "unauthorized"),
)

/**
 * A job with its steps, as shown on its job page.
 */
data class JobDetail(
    val id: String,
    val name: String,
    val type: String?,
    val status: RunStatus,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val executions: List<JobExecution> = emptyList(),
)

/**
 * One parallel execution of a job, with the steps it ran.
 */
data class JobExecution(
    val index: Int,
    val steps: List<Step> = emptyList(),
)

data class Step(
    val num: Int,
    val name: String,
    val type: String?,
    val status: RunStatus,
    val exitCode: Int?,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val stdoutBytes: Long?,
    val stderrBytes: Long?,
)

/**
 * Test result information
 */
data class TestResult(
    val name: String?,
    val classname: String?,
    val file: String?,
    // "success", "failure", "skipped"
    val result: String?,
    val message: String?,
    val source: String?,
    val runTime: Double?,
    val flaky: Boolean?,
)

/**
 * Artifact information
 */
data class Artifact(
    val path: String?,
    val nodeIndex: Int?,
    val url: String?,
    val prettyPath: String?,
)
