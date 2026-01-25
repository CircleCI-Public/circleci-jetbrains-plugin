package com.circleci.idea.filter

import com.circleci.idea.api.models.PipelineInfo
import com.circleci.idea.git.GitBranchService
import com.circleci.idea.state.BranchFilter
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.state.FiltersState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Service for filtering pipelines based on user-selected criteria.
 */
@Service(Service.Level.PROJECT)
class PipelineFilterService(private val project: Project) {

    private val stateStore = CircleCIStateStore.getInstance(project)
    private val gitService = GitBranchService.getInstance(project)

    /**
     * Get the branch name to filter by based on current filter state.
     * Returns null if filtering by all branches.
     */
    fun getBranchForFilter(projectSlug: String? = null): String? {
        val filters = stateStore.filters.value

        return when (filters.branchFilter) {
            BranchFilter.ALL -> null // All branches
            BranchFilter.CURRENT -> gitService.getCurrentBranch(projectSlug)
            BranchFilter.DEFAULT -> gitService.getDefaultBranch(projectSlug)
            BranchFilter.CUSTOM -> filters.authorFilter // Reusing authorFilter for custom branch
        }
    }

    /**
     * Filter a list of pipelines based on current filter state.
     */
    fun filterPipelines(pipelines: List<PipelineInfo>, currentUserId: String? = null): List<PipelineInfo> {
        val filters = stateStore.filters.value
        var filtered = pipelines

        // Filter by status
        if (filters.statusFilter.isNotEmpty()) {
            filtered = filtered.filter { pipeline ->
                filters.statusFilter.contains(pipeline.state)
            }
        }

        // Filter by author (my pipelines only)
        if (filters.myPipelinesOnly && currentUserId != null) {
            filtered = filtered.filter { pipeline ->
                pipeline.trigger?.actor?.login == currentUserId
            }
        }

        return filtered
    }

    /**
     * Check if any filters are currently active.
     */
    fun hasActiveFilters(): Boolean {
        val filters = stateStore.filters.value
        return filters.branchFilter != BranchFilter.ALL ||
               filters.statusFilter.isNotEmpty() ||
               filters.myPipelinesOnly
    }

    /**
     * Get a human-readable description of active filters.
     */
    fun getActiveFiltersDescription(): String {
        val filters = stateStore.filters.value
        val parts = mutableListOf<String>()

        when (filters.branchFilter) {
            BranchFilter.CURRENT -> {
                val branch = gitService.getCurrentBranch()
                parts.add("Branch: ${branch ?: "current"}")
            }
            BranchFilter.DEFAULT -> {
                val branch = gitService.getDefaultBranch()
                parts.add("Branch: $branch")
            }
            BranchFilter.CUSTOM -> {
                filters.authorFilter?.let { parts.add("Branch: $it") }
            }
            BranchFilter.ALL -> {} // No filter
        }

        if (filters.statusFilter.isNotEmpty()) {
            parts.add("Status: ${filters.statusFilter.joinToString(", ")}")
        }

        if (filters.myPipelinesOnly) {
            parts.add("My pipelines only")
        }

        return if (parts.isEmpty()) {
            "No filters active"
        } else {
            parts.joinToString(" | ")
        }
    }

    companion object {
        fun getInstance(project: Project): PipelineFilterService {
            return project.getService(PipelineFilterService::class.java)
        }
    }
}
