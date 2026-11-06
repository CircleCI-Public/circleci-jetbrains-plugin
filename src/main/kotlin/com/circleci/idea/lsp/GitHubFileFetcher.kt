package com.circleci.idea.lsp

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.Flow

/** What fetching a file on GitHub for the language server came to. */
sealed interface GitHubFile {
    data class Found(val source: String) : GitHubFile

    /** There's no account to fetch it as, or the account can't see it. */
    data object NotFound : GitHubFile
}

/**
 * Fetches files on GitHub for the language server: orbs referenced by the URL of
 * a private repository, which it can't fetch itself without a GitHub token.
 */
interface GitHubFileFetcher {
    companion object {
        val EP_NAME = ExtensionPointName<GitHubFileFetcher>("com.circleci.idea.gitHubFileFetcher")
    }

    /** Whether this IDE can fetch files for the language server at all. */
    fun isAvailable(): Boolean

    /** Emits each time the account files are fetched as changes, as when signing in or out. */
    fun accountChanges(project: Project): Flow<Unit>

    /** Fetches [url], a raw.githubusercontent.com or github.com URL. Blocks, so off the EDT. */
    fun fetch(
        project: Project,
        url: String,
    ): GitHubFile
}
