package com.circleci.idea.toolwindow.actions

import com.circleci.idea.api.ChangeService
import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.job.JobDetailsService
import com.circleci.idea.job.JobRef
import com.circleci.idea.run.RunStatus
import com.circleci.idea.run.RunWebUrls
import com.circleci.idea.toolwindow.CircleCIToolWindowService
import com.circleci.idea.toolwindow.tree.JobNode
import com.circleci.idea.toolwindow.tree.WorkflowNode
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import java.awt.datatransfer.StringSelection

/**
 * Base class for job actions.
 */
abstract class JobAction(
    text: String,
    description: String,
    icon: javax.swing.Icon? = null,
) : AnAction(text, description, icon), DumbAware {
    /**
     * Get the job node from the action event.
     */
    protected fun getJobNode(e: AnActionEvent): JobNode? {
        val project = e.project ?: return null
        val toolWindowService = project.getService(CircleCIToolWindowService::class.java)
        return toolWindowService.getSelectedNode() as? JobNode
    }

    /**
     * Get the parent workflow node for a job node.
     */
    protected fun getWorkflowNode(jobNode: JobNode): WorkflowNode? {
        return jobNode.parent as? WorkflowNode
    }

    /**
     * Execute an API action with error handling and tree refresh, under
     * the IDE's progress indicator titled [progressTitle].
     */
    protected fun executeAction(
        e: AnActionEvent,
        confirmMessage: String?,
        progressTitle: String,
        action: suspend () -> Result<Unit>,
    ) {
        val project = e.project ?: return

        // Show confirmation dialog if needed
        if (confirmMessage != null) {
            val result =
                Messages.showYesNoDialog(
                    project,
                    confirmMessage,
                    "Confirm Action",
                    Messages.getQuestionIcon(),
                )
            if (result != Messages.YES) {
                return
            }
        }

        ChangeService.getInstance(project).launch(progressTitle, action) { result ->
            if (result.isSuccess) {
                // Refresh the tree in place to show the updated state
                project.getService(CircleCIToolWindowService::class.java).refreshRuns()
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                Messages.showErrorDialog(
                    project,
                    "Failed to perform action: $error",
                    "Action Failed",
                )
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val jobNode = getJobNode(e)
        e.presentation.isEnabled = jobNode != null && isEnabledForJob(jobNode)
    }

    /**
     * Override to add custom enable logic based on job state.
     */
    protected open fun isEnabledForJob(job: JobNode): Boolean = true
}

/**
 * Action to open a job's page.
 */
class OpenJobDetailsAction : JobAction(
    "Open Job",
    "Open this job's page, with its steps and output",
    AllIcons.General.Information,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val jobNode = getJobNode(e) ?: return
        JobDetailsService.getInstance(project).openJob(JobRef.of(jobNode.job, getWorkflowNode(jobNode)?.workflow))
    }

    override fun isEnabledForJob(job: JobNode): Boolean = job.job.isBuild
}

/**
 * Action to approve an approval job, so its workflow carries on.
 */
class ApproveJobAction : JobAction(
    "Approve",
    "Approve this job, so its workflow carries on",
    AllIcons.Actions.Checked,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val jobNode = getJobNode(e) ?: return
        val job = jobNode.job
        executeAction(e, "Approve '${job.name}'?", "Approving '${job.name}'") {
            // An approval job's ID is its approval request's.
            CircleCIApiService.getInstance().approveWorkflow(job.workflowId, job.id)
        }
    }

    override fun isEnabledForJob(job: JobNode): Boolean = job.job.awaitsApproval
}

/**
 * Action to rerun a job with SSH enabled.
 */
class RerunJobWithSshAction : JobAction(
    "Rerun with SSH",
    "Rerun this job with SSH access enabled",
    AllIcons.Actions.RestartDebugger,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val jobNode = getJobNode(e) ?: return
        val workflowNode = getWorkflowNode(jobNode) ?: return

        val job = jobNode.job
        val workflow = workflowNode.workflow

        executeAction(
            e,
            "Rerun job '${job.name}' with SSH enabled?\n\n" +
                "This will rerun the entire workflow with SSH access enabled for this specific job.",
            "Rerunning job '${job.name}' with SSH",
            {
                CircleCIApiService.getInstance().rerunWorkflow(
                    workflowId = workflow.id,
                    fromFailed = false,
                    enableSsh = true,
                    jobs = listOf(job.id),
                )
            },
        )
    }

    override fun isEnabledForJob(job: JobNode): Boolean {
        return job.job.isBuild && !job.job.status.isActive
    }
}

/**
 * Action to cancel a running job.
 */
class CancelJobAction : JobAction(
    "Cancel Job",
    "Cancel this running job",
    AllIcons.Actions.Suspend,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val jobNode = getJobNode(e) ?: return
        val job = jobNode.job

        executeAction(
            e,
            "Cancel job '${job.name}'?",
            "Canceling job '${job.name}'",
            {
                val jobNumber = job.number
                val projectSlug = job.projectSlug
                if (jobNumber != null && projectSlug != null) {
                    CircleCIApiService.getInstance().cancelJob(projectSlug, jobNumber)
                } else {
                    Result.failure(IllegalStateException("Job number is not available"))
                }
            },
        )
    }

    override fun isEnabledForJob(job: JobNode): Boolean {
        // Can only cancel jobs that are running
        return job.job.status in setOf(RunStatus.RUNNING, RunStatus.FAILING, RunStatus.QUEUED)
    }
}

/**
 * Action to copy job number to clipboard.
 */
class CopyJobNumberAction : JobAction(
    "Copy Job Number",
    "Copy job number to clipboard",
    AllIcons.Actions.Copy,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val jobNode = getJobNode(e) ?: return
        val job = jobNode.job
        val jobNumber = job.number

        if (jobNumber != null) {
            val stringSelection = StringSelection(jobNumber.toString())
            CopyPasteManager.getInstance().setContents(stringSelection)

            // Show a subtle notification
            Messages.showInfoMessage(
                e.project,
                "Job number $jobNumber copied to clipboard",
                "Copied",
            )
        } else {
            Messages.showErrorDialog(
                e.project,
                "Job number is not available for this job",
                "Copy Failed",
            )
        }
    }

    override fun isEnabledForJob(job: JobNode): Boolean {
        // Only enable if job has a number
        return job.job.number != null
    }
}

/**
 * Action to copy a job's ID to the clipboard.
 */
class CopyJobIdAction : JobAction(
    "Copy Job ID",
    "Copy this job's ID to the clipboard",
    AllIcons.Actions.Copy,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val jobNode = getJobNode(e) ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(jobNode.job.id))
    }
}

/**
 * Action to open job in CircleCI web browser.
 */
class OpenJobInBrowserAction : JobAction(
    "Open in Browser",
    "Open this job in CircleCI web interface",
    AllIcons.Ide.External_link_arrow,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val jobNode = getJobNode(e) ?: return
        val url = RunWebUrls.job(jobNode.job, getWorkflowNode(jobNode)?.workflow)

        if (url != null) {
            com.intellij.ide.BrowserUtil.browse(url)
        } else {
            Messages.showErrorDialog(
                e.project,
                "Cannot open job in browser: job number is not available",
                "Open Failed",
            )
        }
    }

    override fun isEnabledForJob(job: JobNode): Boolean {
        // The web app has a page for build jobs, by number
        return job.job.isBuild && job.job.number != null
    }
}

/**
 * Action to rerun the entire workflow from start (for jobs).
 */
class RerunWorkflowFromJobAction : JobAction(
    "Rerun Workflow",
    "Rerun the entire workflow from the beginning",
    AllIcons.Actions.Execute,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val jobNode = getJobNode(e) ?: return
        val workflowNode = getWorkflowNode(jobNode) ?: return

        val workflow = workflowNode.workflow

        executeAction(
            e,
            "Rerun workflow '${workflow.name}' from start?",
            "Rerunning workflow '${workflow.name}'",
            {
                CircleCIApiService.getInstance().rerunWorkflow(
                    workflowId = workflow.id,
                    fromFailed = false,
                )
            },
        )
    }

    override fun isEnabledForJob(job: JobNode): Boolean {
        // Can rerun workflow if job is completed
        val workflowNode = getWorkflowNode(job)
        return workflowNode != null &&
            workflowNode.workflow.status.isRerunnable
    }
}
