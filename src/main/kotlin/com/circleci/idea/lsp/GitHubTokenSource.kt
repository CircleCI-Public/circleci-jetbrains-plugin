package com.circleci.idea.lsp

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.Flow

/**
 * Where the language server's GitHub token comes from, which it fetches orbs
 * referenced by the URL of a private repository with.
 */
interface GitHubTokenSource {
    companion object {
        val EP_NAME = ExtensionPointName<GitHubTokenSource>("com.circleci.idea.gitHubTokenSource")
    }

    /** The token, or null without one, and each one it changes to. */
    fun tokens(project: Project): Flow<String?>
}
