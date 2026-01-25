package com.circleci.idea.state

import kotlinx.coroutines.flow.StateFlow

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
    val error: String? = null
)

data class User(
    val id: String,
    val login: String,
    val name: String
)

/**
 * Projects state - list of selected/followed projects.
 */
data class ProjectsState(
    val selectedProjects: List<String> = emptyList(), // Project slugs
    val followedProjects: List<Project> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

data class Project(
    val slug: String,
    val name: String,
    val organization: String,
    val vcsProvider: String,
    val defaultBranch: String?,
    val followed: Boolean = false
)

/**
 * Projects data state - actual pipeline/workflow/job data for each project.
 */
data class ProjectsDataState(
    val data: Map<String, ProjectData> = emptyMap(), // Key: project slug
    val isRefreshing: Boolean = false,
    val lastRefresh: Long? = null,
    val error: String? = null
)

data class ProjectData(
    val projectSlug: String,
    val pipelines: List<Pipeline> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val nextPageToken: String? = null
)

data class Pipeline(
    val id: String,
    val number: Int,
    val projectSlug: String,
    val state: String,
    val createdAt: String,
    val branch: String?,
    val vcs: VcsInfo?,
    val trigger: TriggerInfo?,
    val workflows: List<Workflow> = emptyList()
)

data class VcsInfo(
    val branch: String?,
    val revision: String?,
    val commit: CommitInfo?,
    val providerName: String?
)

data class CommitInfo(
    val subject: String?,
    val body: String?
)

data class TriggerInfo(
    val type: String?,
    val actor: Actor?
)

data class Actor(
    val login: String?,
    val avatarUrl: String?
)

data class Workflow(
    val id: String,
    val name: String,
    val status: String,
    val createdAt: String,
    val stoppedAt: String?,
    val jobs: List<Job> = emptyList()
)

data class Job(
    val id: String,
    val jobNumber: Long?,
    val name: String,
    val status: String,
    val type: String,
    val startedAt: String?,
    val stoppedAt: String?
)

/**
 * Configuration state - parsed CircleCI config for workspace.
 */
data class ConfigState(
    val configPath: String? = null,
    val isValid: Boolean? = null,
    val errors: List<ConfigError> = emptyList(),
    val lastValidation: Long? = null
)

data class ConfigError(
    val message: String,
    val line: Int? = null,
    val column: Int? = null
)

/**
 * Filters state - user-selected filters for pipelines.
 */
data class FiltersState(
    val branchFilter: BranchFilter = BranchFilter.CURRENT,
    val myPipelinesOnly: Boolean = false,
    val statusFilter: Set<String> = emptySet(), // Empty = all statuses
    val authorFilter: String? = null
)

enum class BranchFilter {
    CURRENT,
    ALL,
    DEFAULT,
    CUSTOM
}

/**
 * UI state - transient UI-specific state.
 */
data class UIState(
    val expandedItems: Set<String> = emptySet(), // Item IDs that are expanded in tree
    val selectedItem: String? = null, // Currently selected item ID
    val isToolWindowVisible: Boolean = false,
    val notificationPreferences: NotificationPreferences = NotificationPreferences()
)

data class NotificationPreferences(
    val enabled: Boolean = true,
    val myPipelinesOnly: Boolean = false,
    val statusFilter: Set<String> = setOf("failed", "failing", "canceled", "on_hold", "error", "unauthorized")
)
