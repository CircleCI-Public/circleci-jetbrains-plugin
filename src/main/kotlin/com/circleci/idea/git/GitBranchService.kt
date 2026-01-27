package com.circleci.idea.git

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Service to interact with Git repositories and get branch information.
 */
@Service(Service.Level.PROJECT)
class GitBranchService(private val project: Project) {
    /**
     * Get the current branch name for the given project slug.
     * Returns null if no git repository is found or if there's an error.
     * Requires Git4Idea plugin to be installed.
     */
    fun getCurrentBranch(projectSlug: String? = null): String? {
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
            null
        }
    }

    /**
     * Get default branch for a repository.
     * Returns "main" as fallback.
     */
    fun getDefaultBranch(projectSlug: String? = null): String {
        return "main" // Simple fallback for now
    }

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
            false
        }
    }

    companion object {
        fun getInstance(project: Project): GitBranchService {
            return project.getService(GitBranchService::class.java)
        }
    }
}
