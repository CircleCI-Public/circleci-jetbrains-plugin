package com.circleci.idea.job

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.icons.CircleCIIcons
import com.circleci.idea.run.RunWebUrls
import com.circleci.idea.ssh.SshSessionService
import com.circleci.idea.state.JobDetail
import com.circleci.idea.toolwindow.CircleCIToolWindowService
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import javax.swing.Icon

/**
 * The actions on a job page's toolbar.
 */
object JobActions {
    fun group(panel: JobPanel): DefaultActionGroup =
        DefaultActionGroup().apply {
            add(RefreshJobAction(panel))
            addSeparator()
            add(RerunWorkflowAction(panel, fromFailed = false))
            add(RerunWorkflowAction(panel, fromFailed = true))
            add(RerunWithSshAction(panel))
            add(CancelJobAction(panel))
            addSeparator()
            add(ConnectSshAction(panel))
            add(CopySshCommandAction(panel))
            add(OpenJobInBrowserAction(panel))
        }
}

private abstract class JobPageAction(
    protected val panel: JobPanel,
    text: String,
    description: String,
    icon: Icon,
) : AnAction(text, description, icon), DumbAware {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = isEnabled(panel.detail)
    }

    protected open fun isEnabled(detail: JobDetail?): Boolean = true

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    /**
     * Confirm, then run an API call off the EDT; report the outcome and
     * refresh the job page and run list.
     */
    protected fun confirmAndRun(
        e: AnActionEvent,
        confirmMessage: String,
        successMessage: String,
        call: () -> Result<Unit>,
    ) {
        val project = e.project ?: return
        if (Messages.showYesNoDialog(project, confirmMessage, "Confirm", Messages.getQuestionIcon()) != Messages.YES) {
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = call()
            ApplicationManager.getApplication().invokeLater {
                result.fold(
                    onSuccess = {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("CircleCI Notifications")
                            .createNotification(successMessage, NotificationType.INFORMATION)
                            .notify(project)
                        panel.refresh()
                        project.getService(CircleCIToolWindowService::class.java).refreshRuns()
                    },
                    onFailure = { Messages.showErrorDialog(project, it.message ?: "Unknown error", "Action Failed") },
                )
            }
        }
    }
}

private class RefreshJobAction(panel: JobPanel) :
    JobPageAction(panel, "Refresh", "Reload this job", AllIcons.Actions.Refresh) {
    override fun actionPerformed(e: AnActionEvent) {
        panel.refresh()
    }
}

private class RerunWorkflowAction(panel: JobPanel, private val fromFailed: Boolean) :
    JobPageAction(
        panel,
        if (fromFailed) "Rerun Workflow from Failed" else "Rerun Workflow",
        "Rerun this job's workflow from " + if (fromFailed) "its failed jobs" else "the start",
        if (fromFailed) AllIcons.Actions.Restart else AllIcons.Actions.Execute,
    ) {
    override fun isEnabled(detail: JobDetail?): Boolean {
        val status = detail?.status ?: return false
        return if (fromFailed) status.isFailure else !status.isActive
    }

    override fun actionPerformed(e: AnActionEvent) {
        val workflow = panel.ref.workflowName?.let { "'$it'" } ?: "this job's workflow"
        confirmAndRun(
            e,
            "Rerun $workflow${if (fromFailed) " from its failed jobs" else " from the start"}?",
            "Rerunning $workflow",
        ) { CircleCIApiService.getInstance().rerunWorkflow(panel.ref.workflowId, fromFailed = fromFailed) }
    }
}

private class RerunWithSshAction(panel: JobPanel) :
    JobPageAction(
        panel,
        "Rerun with SSH",
        "Rerun this job's workflow with SSH enabled",
        AllIcons.Actions.RestartDebugger,
    ) {
    override fun isEnabled(detail: JobDetail?): Boolean = detail != null && !detail.status.isActive

    override fun actionPerformed(e: AnActionEvent) {
        confirmAndRun(
            e,
            "Rerun '${panel.ref.name}' with SSH enabled?\n\nThis reruns the whole workflow.",
            "Rerunning '${panel.ref.name}' with SSH. Once it starts, use SSH into Job on its new job page.",
        ) {
            CircleCIApiService.getInstance().rerunWorkflow(
                panel.ref.workflowId,
                fromFailed = false,
                enableSsh = true,
                jobs = listOf(panel.ref.jobId),
            )
        }
    }
}

private class CancelJobAction(panel: JobPanel) :
    JobPageAction(panel, "Cancel Job", "Cancel this job", AllIcons.Actions.Suspend) {
    override fun isEnabled(detail: JobDetail?): Boolean {
        return detail?.status?.isActive == true && panel.ref.number != null && panel.ref.projectSlug != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val number = panel.ref.number ?: return
        val projectSlug = panel.ref.projectSlug ?: return
        confirmAndRun(e, "Cancel job '${panel.ref.name}'?", "Canceling '${panel.ref.name}'") {
            CircleCIApiService.getInstance().cancelJob(projectSlug, number)
        }
    }
}

/** The selected step's execution of the job, to SSH into. */
private fun sshJob(panel: JobPanel): SshSessionService.Job =
    SshSessionService.Job(panel.ref.jobId, panel.selectedExecution, panel.ref.name, panel.ref.number)

/**
 * Opens an SSH session into the selected step's execution, in a Terminal
 * tab. It only connects to a job rerun with SSH, while it runs.
 */
private class ConnectSshAction(panel: JobPanel) :
    JobPageAction(
        panel,
        "SSH into Job",
        "Open an SSH session into this job in the Terminal (for jobs rerun with SSH, while they run)",
        CircleCIIcons.Actions.SSH,
    ) {
    override fun isEnabled(detail: JobDetail?): Boolean = detail?.status?.isActive == true

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SshSessionService.getInstance(project).open(sshJob(panel))
    }
}

/**
 * Copies the command to SSH into the selected step's execution, as its
 * "Enable SSH" step printed it. Only a job rerun with SSH has one.
 */
private class CopySshCommandAction(panel: JobPanel) :
    JobPageAction(
        panel,
        "Copy SSH Command",
        "Copy the command to SSH into this job (for jobs rerun with SSH, while they run)",
        AllIcons.Actions.Copy,
    ) {
    override fun isEnabled(detail: JobDetail?): Boolean = detail?.status?.isActive == true

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SshSessionService.getInstance(project).copyCommand(sshJob(panel))
    }
}

private class OpenJobInBrowserAction(panel: JobPanel) :
    JobPageAction(panel, "Open in Browser", "Open this job in the CircleCI web app", AllIcons.Ide.External_link_arrow) {
    override fun isEnabled(detail: JobDetail?): Boolean = url() != null

    override fun actionPerformed(e: AnActionEvent) {
        url()?.let { BrowserUtil.browse(it) }
    }

    private fun url(): String? {
        val ref = panel.ref
        return RunWebUrls.job(ref.projectSlug, ref.number, ref.workflowId, ref.runNumber)
    }
}
