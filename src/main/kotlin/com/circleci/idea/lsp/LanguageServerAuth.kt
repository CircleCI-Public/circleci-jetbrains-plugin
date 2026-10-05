package com.circleci.idea.lsp

import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.settings.CircleCISettings
import com.circleci.idea.state.CircleCIStateStore
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspClientManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.ExecuteCommandParams

/** The CircleCI token, empty when logged out, and the host it's for. */
internal data class LanguageServerCredentials(val token: String, val hostUrl: String)

/**
 * The commands the language server takes the token and host by, in the order the
 * VS Code extension sends them. It doesn't read them from its environment.
 */
internal fun authCommands(credentials: LanguageServerCredentials): List<ExecuteCommandParams> =
    listOf(
        ExecuteCommandParams("setToken", listOf(credentials.token)),
        ExecuteCommandParams("setSelfHostedUrl", listOf(credentials.hostUrl)),
    )

/**
 * Gives the project's CircleCI language servers the token and host: each as it
 * starts, and all of them again when you log in or out, or change the host.
 */
@Service(Service.Level.PROJECT)
class LanguageServerAuth(private val project: Project, private val scope: CoroutineScope) {
    companion object {
        fun getInstance(project: Project): LanguageServerAuth = project.service()
    }

    init {
        // A server is sent the credentials it starts with by [sendTo], so only changes go from here.
        scope.launch(Dispatchers.IO) {
            CircleCIStateStore.getInstance(project).auth
                .map { credentials() }
                .distinctUntilChanged()
                .drop(1)
                .collect { credentials -> clients().forEach { send(it, credentials) } }
        }
    }

    /** Send the credentials to the server just started for [descriptor]. */
    fun sendTo(descriptor: LspClientDescriptor) {
        scope.launch(Dispatchers.IO) {
            val credentials = credentials()
            clients().filter { it.descriptor === descriptor }.forEach { send(it, credentials) }
        }
    }

    /** Send the credentials to every server, after the host has changed in Settings. */
    fun hostChanged() {
        scope.launch(Dispatchers.IO) {
            val credentials = credentials()
            clients().forEach { send(it, credentials) }
        }
    }

    private fun clients(): Collection<LspClient> =
        LspClientManager.getInstance(project).getClients(CircleCILspIntegrationProvider::class.java)

    // Reads the password safe, so off the EDT.
    private fun credentials() =
        LanguageServerCredentials(
            token = CircleCIAuthService.getInstance(project).getToken().orEmpty(),
            hostUrl = CircleCISettings.getInstance().hostUrl,
        )

    private suspend fun send(
        client: LspClient,
        credentials: LanguageServerCredentials,
    ) {
        for (command in authCommands(credentials)) {
            client.sendRequest { it.workspaceService.executeCommand(command) }
        }
    }
}
