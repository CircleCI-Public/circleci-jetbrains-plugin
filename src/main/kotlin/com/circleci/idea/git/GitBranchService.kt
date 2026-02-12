package com.circleci.idea.git

import com.circleci.idea.logging.CircleCILogger
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Service to interact with Git repositories and get branch information.
 */
@Service(Service.Level.PROJECT)
class GitBranchService(private val project: Project) {
    private val logger = CircleCILogger.getInstance()

    /**
     * Get the current branch name for the given project slug.
     * Returns null if no git repository is found or if there's an error.
     * Requires Git4Idea plugin to be installed.
     */
    fun getCurrentBranch(
        @Suppress("UNUSED_PARAMETER") _projectSlug: String? = null,
    ): String? {
        return try {
            // Use reflection to access Git4Idea classes to avoid hard dependency
            val gitRepositoryManagerClass = Class.forName("git4idea.repo.GitRepositoryManager")
            val getInstanceMethod = gitRepositoryManagerClass.getMethod("getInstance", Project::class.java)
            val manager = getInstanceMethod.invoke(null, project)
            val getRepositoriesMethod = gitRepositoryManagerClass.getMethod("getRepositories")
            val repositories = getRepositoriesMethod.invoke(manager) as List<*>

            val repository = repositories.firstOrNull()
            if (repository != null) {
                val getCurrentBranchMethod = repository.javaClass.getMethod("getCurrentBranchName")
                getCurrentBranchMethod.invoke(repository) as? String
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug("Failed to get current branch (Git4Idea not available or no repository)", e)
            null
        }
    }

    /**
     * Get default branch for a repository.
     * Returns "main" as fallback.
     */
    fun getDefaultBranch(
        @Suppress("UNUSED_PARAMETER") _projectSlug: String? = null,
    ): String = DEFAULT_BRANCH

    /**
     * Check if Git is available in this project.
     */
    fun isGitAvailable(): Boolean {
        return try {
            val gitRepositoryManagerClass = Class.forName("git4idea.repo.GitRepositoryManager")
            val getInstanceMethod = gitRepositoryManagerClass.getMethod("getInstance", Project::class.java)
            val manager = getInstanceMethod.invoke(null, project)
            val getRepositoriesMethod = gitRepositoryManagerClass.getMethod("getRepositories")
            val repositories = getRepositoriesMethod.invoke(manager) as List<*>
            repositories.isNotEmpty()
        } catch (e: Exception) {
            logger.debug("Git not available (Git4Idea plugin not found or no repository)", e)
            false
        }
    }

    companion object {
        private const val DEFAULT_BRANCH = "main"

        fun getInstance(project: Project): GitBranchService {
            return project.getService(GitBranchService::class.java)
        }
    }
}
