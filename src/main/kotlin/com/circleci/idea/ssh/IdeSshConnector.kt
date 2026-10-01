package com.circleci.idea.ssh

import com.intellij.openapi.project.Project
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
        TerminalToolWindowManager.getInstance(project).createNewSession(SshTerminalRunner(project, target), tab)
    }
}

/**
 * Runs a Terminal tab's session as an SSH shell on a job, rather than a local shell.
 */
private class SshTerminalRunner(
    project: Project,
    private val target: SshTarget,
) : AbstractTerminalRunner<SshShellProcess>(project) {
    override fun createTtyConnector(options: ShellStartupOptions): TtyConnector =
        SshTtyConnector(connect(), target.title)

    private fun connect(): SshShellProcess {
        // Nothing stored (password, key path, passphrase): the IDE prompts for what it needs.
        val noStoredSecret = { null }
        val passwords =
            PlatformSshPasswordProvider(
                SshTarget.HOST,
                SshTarget.PORT,
                target.user,
                noStoredSecret,
                noStoredSecret,
                noStoredSecret,
            )
        return try {
            ConnectionBuilder(SshTarget.HOST)
                .withSshConnectionConfig { it.copy(user = target.user, port = SshTarget.PORT) }
                .withParsingOpenSSHConfig(true)
                .withSshPasswordProvider(passwords)
                .shellBuilder()
                .withAllocatePty(true)
                .execute()
        } catch (e: SshException) {
            throw ExecutionException("Couldn't connect to ${target.command}: ${e.message}\n\n$CONNECT_HINT", e)
        }
    }

    override fun getDefaultTabTitle(): String = target.title

    // A job's shell doesn't outlive the job, so there's no session to restore on restart.
    override fun isTerminalSessionPersistent(): Boolean = false

    private companion object {
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
