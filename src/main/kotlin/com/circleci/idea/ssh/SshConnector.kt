package com.circleci.idea.ssh

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * One execution of a job to SSH into, at the [endpoint] its "Enable SSH"
 * step printed. A job accepts SSH once it's rerun with SSH, with the SSH key
 * of the account that reran it.
 */
data class SshTarget(
    val jobId: String,
    val execution: Int,
    val jobName: String,
    val jobNumber: Long?,
    val endpoint: SshEndpoint,
) {
    /** The equivalent OpenSSH command, to copy. */
    val command: String
        get() = endpoint.command

    /** A session tab's title: the job, and the execution when that matters. */
    val title: String
        get() = jobName + (jobNumber?.let { " #$it" } ?: "") + if (execution > 0) " (execution $execution)" else ""
}

/**
 * Opens SSH sessions into jobs, in tabs of the IDE's Terminal.
 *
 * Implemented with the IDE's SSH client and Terminal, which are bundled
 * plugins that can be disabled; so this is only registered when both are
 * present (see ssh-support.xml), and [getInstance] is null otherwise.
 */
interface SshConnector {
    /** Open a new Terminal tab connecting to [target]. Call on the EDT. */
    fun openSession(
        project: Project,
        target: SshTarget,
    )

    companion object {
        fun getInstance(): SshConnector? = ApplicationManager.getApplication().getService(SshConnector::class.java)
    }
}
