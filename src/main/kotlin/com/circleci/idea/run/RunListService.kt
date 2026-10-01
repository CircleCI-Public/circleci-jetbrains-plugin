package com.circleci.idea.run

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.api.clients.V3Page
import com.circleci.idea.git.GitBranchService
import com.circleci.idea.project.models.CircleCIProject
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.state.FiltersState
import com.circleci.idea.state.Job
import com.circleci.idea.state.Run
import com.circleci.idea.state.Workflow
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Lists runs, workflows and jobs for the CircleCI tool window, applying the current
 * filters the way `circleci run get` does: a project's runs come from
 * runs/search (scoped to a branch, status and created window), and "my runs"
 * from the cross-project my-runs listing.
 */
@Service(Service.Level.PROJECT)
class RunListService(private val project: Project) {
    private val apiService = CircleCIApiService.getInstance()
    private val stateStore = CircleCIStateStore.getInstance(project)
    private val gitService = GitBranchService.getInstance(project)

    private val projectIds = ConcurrentHashMap<String, String>()
    private val projectSlugs = ConcurrentHashMap<String, String>()

    /** What a project's run list is scoped to, under the current filters. */
    sealed class BranchScope {
        data class Branch(val name: String) : BranchScope()

        object AllBranches : BranchScope()

        /** The current-branch scope, with no branch checked out. */
        object NoCurrentBranch : BranchScope()
    }

    fun branchScope(circleCIProject: CircleCIProject): BranchScope {
        return when (filters().scope) {
            RunScope.CURRENT_BRANCH ->
                gitService.getCurrentBranchForRuns(circleCIProject.localPath)?.let { BranchScope.Branch(it) }
                    ?: BranchScope.NoCurrentBranch
            RunScope.DEFAULT_BRANCH ->
                BranchScope.Branch(
                    circleCIProject.defaultBranch ?: gitService.getDefaultBranch(circleCIProject.localPath),
                )
            RunScope.ALL_BRANCHES, RunScope.MY_RUNS -> BranchScope.AllBranches
        }
    }

    /**
     * Fetch a page of a project's runs, newest first.
     */
    suspend fun fetchProjectRuns(
        circleCIProject: CircleCIProject,
        cursor: String? = null,
    ): Result<V3Page<Run>> {
        val branch =
            when (val scope = branchScope(circleCIProject)) {
                is BranchScope.Branch -> scope.name
                BranchScope.AllBranches -> null
                BranchScope.NoCurrentBranch -> return Result.failure(IllegalStateException("No branch checked out"))
            }
        val filters = filters()
        val window = RunQueries.window(filters.created, Instant.now())

        return withContext(Dispatchers.IO) {
            projectId(circleCIProject.slug).mapCatching { projectId ->
                val page =
                    apiService.searchRuns(
                        projectId = projectId,
                        from = window.from,
                        to = window.to,
                        filter = RunQueries.filterExpression(branch, filters.status),
                        limit = RunQueries.MAX_PAGE_SIZE,
                        cursor = cursor,
                    ).getOrThrow()
                V3Page(page.items.mapNotNull { RunMapper.toRun(it, circleCIProject.slug) }, page.nextCursor)
            }
        }
    }

    /**
     * Fetch a page of the authenticated user's runs across all projects, newest first.
     */
    suspend fun fetchMyRuns(cursor: String? = null): Result<V3Page<Run>> {
        val filters = filters()
        // Unlike runs/search, the my-runs listing needs no window; only bound
        // it when a created filter asks for one.
        val window = filters.created?.let { RunQueries.window(it, Instant.now()) }

        return withContext(Dispatchers.IO) {
            apiService.listMyRuns(
                phase = filters.status?.phase,
                currentOutcome = filters.status?.currentOutcome,
                from = window?.from,
                to = window?.to,
                limit = RunQueries.MAX_PAGE_SIZE,
                cursor = cursor,
            ).map { page -> V3Page(page.items.mapNotNull { RunMapper.toRun(it) }, page.nextCursor) }
        }
    }

    /**
     * Fetch a run's workflows. The run is returned too, with its project slug
     * resolved if the listing couldn't tell it.
     */
    suspend fun fetchWorkflows(run: Run): Result<Pair<Run, List<Workflow>>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val resolvedRun = if (run.projectSlug == null) run.copy(projectSlug = projectSlug(run)) else run
                val workflows =
                    apiService.getRunWorkflows(run.id).getOrThrow()
                        .mapNotNull { RunMapper.toWorkflow(it, resolvedRun) }
                resolvedRun to workflows
            }
        }
    }

    /**
     * Fetch a workflow's jobs.
     */
    suspend fun fetchJobs(workflow: Workflow): Result<List<Job>> {
        return withContext(Dispatchers.IO) {
            apiService.getWorkflowJobs(workflow.id).map { jobs -> jobs.mapNotNull { RunMapper.toJob(it, workflow) } }
        }
    }

    private fun filters(): FiltersState = stateStore.filters.value

    private fun projectId(slug: String): Result<String> {
        projectIds[slug]?.let { return Result.success(it) }
        return apiService.getProjectId(slug).onSuccess { projectIds[slug] = it }
    }

    /**
     * The slug of a project whose runs don't spell it out: those are keyed on
     * the organization and project IDs, "circleci/<org-id>/<project-id>".
     */
    private fun projectSlug(run: Run): String? {
        val projectId = run.projectId ?: return null
        return projectSlugs[projectId]
            ?: apiService.getProjectOrgId(projectId).getOrNull()
                ?.let { orgId -> "circleci/$orgId/$projectId" }
                ?.also { projectSlugs[projectId] = it }
    }

    companion object {
        fun getInstance(project: Project): RunListService {
            return project.getService(RunListService::class.java)
        }
    }
}
