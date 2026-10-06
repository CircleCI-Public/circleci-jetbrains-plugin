package com.circleci.idea.ssh

import com.circleci.idea.logging.CircleCILogger
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ssh.ConnectionBuilder
import com.intellij.ssh.SshException
import com.intellij.ssh.interaction.PlatformSshPasswordProvider
import com.intellij.ssh.process.SshShellProcess
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.TtyConnector
import org.jetbrains.plugins.terminal.AbstractTerminalRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import org.jetbrains.plugins.terminal.TerminalTabState
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.util.concurrent.ExecutionException

/**
 * [SshConnector] on the IDE's SSH client, in the IDE's Terminal. The client
 * reads ~/.ssh/config and uses the SSH agent and keys as OpenSSH would,
 * asking (in the IDE) for a key's passphrase or to trust the host key when
 * it needs to.
 *
 * Only loaded when the IDE's SSH and Terminal plugins are (see ssh-support.xml).
 */
class IdeSshConnector : SshConnector {
    override fun openSession(
        project: Project,
        target: SshTarget,
    ) {
        val tab = TerminalTabState().apply { myTabName = target.title }
        service<SshTabCloser>()
        TerminalToolWindowManager.getInstance(project).createNewSession(SshTerminalRunner(project, target), tab)
    }
}

/**
 * Closes the Terminal's SSH tabs as the plugin unloads, ending their
 * sessions: a tab holds its runner and connection, which would keep the
 * plugin's classes loaded, and the unload would need a restart. Created
 * with the first tab, so it's disposed with the plugin.
 */
@Service(Service.Level.APP)
internal class SshTabCloser : Disposable {
    override fun dispose() {
        for (project in ProjectManager.getInstance().openProjects) {
            val manager = TerminalToolWindowManager.getInstance(project)
            val contents = manager.toolWindow?.contentManager?.contents ?: continue
            contents.filter { TerminalToolWindowManager.getRunnerByContent(it) is SshTerminalRunner }
                .forEach(manager::closeTab)
        }
    }
}

/**
 * Runs a Terminal tab's session as an SSH shell on a job, rather than a local shell.
 */
private class SshTerminalRunner(
    project: Project,
    private val target: SshTarget,
) : AbstractTerminalRunner<SshShellProcess>(project) {
    private val logger = CircleCILogger.getInstance()

    override fun createTtyConnector(options: ShellStartupOptions): TtyConnector =
        SshTtyConnector(connect(), target.title)

    private fun connect(): SshShellProcess {
        val endpoint = target.endpoint
        val port = endpoint.port ?: DEFAULT_PORT
        // The proxy's username names the session; a direct endpoint ignores
        // it, so use the local one, as ssh does.
        val user = (endpoint as? SshEndpoint.Proxy)?.user ?: System.getProperty("user.name")
        // Nothing stored (password, key path, passphrase): the IDE prompts for what it needs.
        val noStoredSecret = { null }
        val passwords =
            PlatformSshPasswordProvider(endpoint.host, port, user, noStoredSecret, noStoredSecret, noStoredSecret)
        return try {
            val connection =
                ConnectionBuilder(endpoint.host)
                    // Set last, so a User in ~/.ssh/config (or the local login name)
                    // can't take the place of the proxy's session username.
                    .withSshConnectionConfig { it.copy(user = user, port = port) }
                    .withParsingOpenSSHConfig(true)
                    .withSshPasswordProvider(passwords)
            checkUser(connection, user)
            connection.shellBuilder()
                .withAllocatePty(true)
                .execute()
        } catch (e: SshException) {
            throw ExecutionException("Couldn't connect to ${target.command}: ${e.message}\n\n$CONNECT_HINT", e)
        }
    }

    /** Make sure the configuration resolves to the user to connect as, and say who that is. */
    private fun checkUser(
        connection: ConnectionBuilder,
        user: String,
    ) {
        val resolved = connection.buildConnectionConfig()
        logger.info("Opening SSH session as ${resolved.user}@${resolved.host}:${resolved.port}")
        if (resolved.user != user) {
            throw ExecutionException(
                "SSH would connect as ${resolved.user} rather than $user. Connect from a terminal with: " +
                    target.command,
                null,
            )
        }
    }

    override fun getDefaultTabTitle(): String = target.title

    // A job's shell doesn't outlive the job, so there's no session to restore on restart.
    override fun isTerminalSessionPersistent(): Boolean = false

    private companion object {
        const val DEFAULT_PORT = 22
        const val CONNECT_HINT =
            "A job accepts SSH connections only after it's rerun with SSH, while it runs (and for a while after its " +
                "steps finish), and only with an SSH key on the account that reran it."
    }
}

/** Joins the terminal to the shell, passing its size on so full-screen programs fit. */
private class SshTtyConnector(
    private val process: SshShellProcess,
    private val title: String,
) : ProcessTtyConnector(process, Charsets.UTF_8) {
    override fun getName(): String = title

    override fun resize(termSize: TermSize) {
        process.setWindowSize(termSize.columns, termSize.rows)
    }
}
