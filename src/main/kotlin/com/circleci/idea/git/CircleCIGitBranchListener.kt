package com.circleci.idea.git

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

/**
 * Passes Git repository changes to [GitBranchService], so the run list follows
 * branch checkouts. Registered in git-support.xml, so only loaded when Git4Idea is.
 */
class CircleCIGitBranchListener(private val project: Project) : GitRepositoryChangeListener {
    override fun repositoryChanged(repository: GitRepository) {
        GitBranchService.getInstance(project).onRepositoryChanged(repository.root.path, repository.currentBranchName)
    }
}
