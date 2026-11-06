package com.circleci.idea.lsp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import java.util.concurrent.CompletableFuture

/** The initialization option that tells the server it can ask us for files on GitHub. */
internal const val FETCHES_GITHUB_FILES_OPTION = "fetchesGitHubFiles"

/** The command that tells the server the account files are fetched as has changed. */
internal const val GITHUB_ACCOUNT_CHANGED_COMMAND = "gitHubAccountChanged"

internal data class FetchGitHubFileParams(val url: String = "")

/** A file on GitHub, as the server takes it: [source] when [found]. */
internal data class FetchGitHubFileResult(val found: Boolean, val source: String? = null)

/**
 * Fetches files on GitHub that the project's language servers can't fetch
 * themselves, such as orbs in private repositories, when this IDE can.
 */
@Service(Service.Level.PROJECT)
class LanguageServerGitHubFiles(private val project: Project, private val scope: CoroutineScope) {
    companion object {
        fun getInstance(project: Project): LanguageServerGitHubFiles = project.service()
    }

    init {
        scope.launch(Dispatchers.IO) {
            fetcher()?.accountChanges(project)?.collect {
                val command = ExecuteCommandParams(GITHUB_ACCOUNT_CHANGED_COMMAND, emptyList())
                clients().forEach { client -> client.sendRequest { it.workspaceService.executeCommand(command) } }
            }
        }
    }

    private fun clients(): Collection<LspClient> =
        LspClientManager.getInstance(project).getClients(CircleCILspIntegrationProvider::class.java)

    private fun fetcher(): GitHubFileFetcher? = GitHubFileFetcher.EP_NAME.extensionList.firstOrNull { it.isAvailable() }

    /** The initialization options for a server: whether to ask us for files on GitHub. */
    internal fun initializationOptions(): Map<String, Any> =
        if (fetcher() != null) mapOf(FETCHES_GITHUB_FILES_OPTION to true) else emptyMap()

    internal fun fetch(url: String): CompletableFuture<FetchGitHubFileResult> =
        scope.future(Dispatchers.IO) {
            when (val file = fetcher()?.fetch(project, url) ?: GitHubFile.NotFound) {
                is GitHubFile.Found -> FetchGitHubFileResult(found = true, source = file.source)
                GitHubFile.NotFound -> FetchGitHubFileResult(found = false)
            }
        }
}

/** Answers the CircleCI language server's own requests, on top of the standard ones. */
internal class CircleCILsp4jClient(
    handler: LspServerNotificationsHandler,
    private val gitHubFiles: LanguageServerGitHubFiles,
) : Lsp4jClient(handler) {
    @JsonRequest("circleci/fetchGitHubFile")
    fun fetchGitHubFile(params: FetchGitHubFileParams): CompletableFuture<FetchGitHubFileResult> =
        gitHubFiles.fetch(params.url)
}
