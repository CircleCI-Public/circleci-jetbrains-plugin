package com.circleci.idea.git

import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.run.RunScope
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.toolwindow.CircleCIToolWindowService
import com.intellij.dvcs.repo.Repository
import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Service to get the current branch of the project's repositories, as the
 * IDE's VCS layer sees it.
 *
 * Branch-change events come from [CircleCIGitBranchListener], which is only
 * registered when Git4Idea is installed.
 */
@Service(Service.Level.PROJECT)
class GitBranchService(private val project: Project) {
    private val logger = CircleCILogger.getInstance()

    // Last branch seen per repository root, by the run list or a Git event,
    // to tell a checkout from the other repository changes (commits,
    // fetches, index updates) Git reports.
    private val branchesByRoot = ConcurrentHashMap<String, String>()

    /**
     * Get the current branch name of the repository at [repositoryPath], or
     * of the first repository when it's null or matches none.
     * Returns null if there's no repository or HEAD is detached.
     */
    fun getCurrentBranch(repositoryPath: String? = null): String? = currentBranch(repositoryPath, record = false)

    /**
     * [getCurrentBranch], for listing runs on: remembers the branch, so a
     * later checkout is seen as a change from what the list shows.
     */
    fun getCurrentBranchForRuns(repositoryPath: String?): String? = currentBranch(repositoryPath, record = true)

    private fun currentBranch(
        repositoryPath: String?,
        record: Boolean,
    ): String? {
        val repositories = repositories()
        val repository =
            repositories.firstOrNull { repositoryPath != null && it.root.path == repositoryPath }
                ?: repositories.firstOrNull()
                ?: return null
        val branch = repository.currentBranchName
        if (record) {
            branchesByRoot[repository.root.path] = branch ?: DETACHED
        }
        return branch
    }

    /**
     * Get default branch for a repository.
     * Returns "main" as fallback.
     */
    fun getDefaultBranch(
        @Suppress("UNUSED_PARAMETER") repositoryPath: String? = null,
    ): String = DEFAULT_BRANCH

    /**
     * Check if the project has a VCS repository.
     */
    fun isGitAvailable(): Boolean = repositories().isNotEmpty()

    /**
     * Record a repository's branch, reloading the run list when it changed
     * and the list is scoped to the current branch.
     */
    fun onRepositoryChanged(
        rootPath: String,
        branch: String?,
    ) {
        // Detached HEAD has no branch; track it as such so leaving it counts as a change.
        val previous = branchesByRoot.put(rootPath, branch ?: DETACHED)
        // The first report for a repository is its starting state, not a checkout.
        if (previous == null || previous == (branch ?: DETACHED)) return

        logger.info("Branch changed in $rootPath: $previous -> ${branch ?: "(detached)"}")
        if (CircleCIStateStore.getInstance(project).filters.value.scope == RunScope.CURRENT_BRANCH) {
            project.getService(CircleCIToolWindowService::class.java).reloadTree()
        }
    }

    private fun repositories(): Collection<Repository> = VcsRepositoryManager.getInstance(project).getRepositories()

    companion object {
        private const val DEFAULT_BRANCH = "main"
        private const val DETACHED = ""

        fun getInstance(project: Project): GitBranchService {
            return project.getService(GitBranchService::class.java)
        }
    }
}
