package com.circleci.idea.ssh

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * Opens SSH sessions into jobs, each in its own Terminal tab.
 */
@Service(Service.Level.PROJECT)
class SshSessionService(private val project: Project) {
    /** Open a new session into [target]. Call on the EDT. */
    fun open(target: SshTarget) {
        val connector = SshConnector.getInstance()
        if (connector == null) {
            Messages.showErrorDialog(
                project,
                "SSH sessions use the IDE's SSH and Terminal plugins; one of them is disabled. Enable them in " +
                    "Settings | Plugins, or connect from a terminal with:\n\n${target.command}",
                "SSH Unavailable",
            )
            return
        }
        connector.openSession(project, target)
    }

    companion object {
        fun getInstance(project: Project): SshSessionService = project.getService(SshSessionService::class.java)
    }
}
