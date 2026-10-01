package com.circleci.idea.toolwindow.actions

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.datatransfer.StringSelection

/**
 * Base class for job actions.
 */
abstract class JobAction(
    text: String,
    description: String,
    icon: javax.swing.Icon? = null,
) : AnAction(text, description, icon), DumbAware {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
     * Execute an API action with error handling and tree refresh.
     */
    protected fun executeAction(
        e: AnActionEvent,
        confirmMessage: String?,
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

        scope.launch {
            // Execute action
            val result =
                withContext(Dispatchers.IO) {
                    action()
                }

            // Handle result
            withContext(Dispatchers.Main) {
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
        // Can only rerun jobs that have completed (not currently running)
        return !job.job.status.isActive
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
        // Only enable if job has a number
        return job.job.number != null
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
