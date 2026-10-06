package com.circleci.idea.ssh

import com.circleci.idea.api.callApi
import com.circleci.idea.job.JobDetailsService
import com.circleci.idea.state.Step
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

/**
 * Opens SSH sessions into jobs, each in its own Terminal tab, at the
 * address the job's "Enable SSH" step printed (see [SshEndpoint]).
 */
@Service(Service.Level.PROJECT)
class SshSessionService(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    /** One execution of a job, before its SSH endpoint is known. */
    data class Job(
        val jobId: String,
        val execution: Int,
        val name: String,
        val number: Long?,
    )

    /** Open a new session into [job], once its "Enable SSH" step says where. Call on the EDT. */
    fun open(job: Job) {
        val connector = SshConnector.getInstance()
        if (connector == null) {
            Messages.showErrorDialog(
                project,
                "SSH sessions use the IDE's SSH and Terminal plugins; one of them is disabled. Enable them in " +
                    "Settings | Plugins, or use Copy SSH Command to connect from a terminal.",
                "SSH Unavailable",
            )
            return
        }
        withTarget(job) { connector.openSession(project, it) }
    }

    /** Copy the command to SSH into [job] from a terminal, as its "Enable SSH" step printed it. */
    fun copyCommand(job: Job) {
        withTarget(job) { target ->
            CopyPasteManager.getInstance().setContents(StringSelection(target.command))
            notify("Copied: ${target.command}", NotificationType.INFORMATION)
        }
    }

    private fun withTarget(
        job: Job,
        use: (SshTarget) -> Unit,
    ) {
        scope.launch(Dispatchers.Main) {
            findEndpoint(job).fold(
                onSuccess = { use(SshTarget(job.jobId, job.execution, job.name, job.number, it)) },
                onFailure = {
                    notify(
                        it.message ?: "Couldn't find where to SSH into the job",
                        NotificationType.WARNING,
                    )
                },
            )
        }
    }

    /**
     * Where the execution's "Enable SSH" step says to connect: it prints the
     * address once the job is up, in one of two formats.
     */
    private suspend fun findEndpoint(job: Job): Result<SshEndpoint> =
        runCatching {
            val step = findEnableSshStep(job)
            val output = callApi(project) { getStepStdout(job.jobId, job.execution, step.num, 0) }.getOrThrow()
            SshEndpoint.parse(String(output.data, Charsets.UTF_8))
                ?: error(
                    if (step.status.isActive) {
                        "${job.name} hasn't printed its SSH details yet. Try again in a moment."
                    } else {
                        "Couldn't find SSH details in the output of ${job.name}'s \"$ENABLE_SSH_STEP\" step."
                    },
                )
        }

    private suspend fun findEnableSshStep(job: Job): Step {
        val detail = JobDetailsService.getInstance(project).fetchJob(job.jobId).getOrThrow()
        val steps = detail.executions.firstOrNull { it.index == job.execution }?.steps.orEmpty()
        return steps.firstOrNull { it.name == ENABLE_SSH_STEP }
            ?: error("${job.name} has no \"$ENABLE_SSH_STEP\" step. Rerun it with SSH, and connect while it runs.")
    }

    private fun notify(
        message: String,
        type: NotificationType,
    ) {
        NotificationGroupManager.getInstance().getNotificationGroup("CircleCI Notifications")
            .createNotification(message, type)
            .notify(project)
    }

    companion object {
        /** The step a job rerun with SSH adds, which prints where to connect. */
        const val ENABLE_SSH_STEP = "Enable SSH"

        fun getInstance(project: Project): SshSessionService = project.getService(SshSessionService::class.java)
    }
}
