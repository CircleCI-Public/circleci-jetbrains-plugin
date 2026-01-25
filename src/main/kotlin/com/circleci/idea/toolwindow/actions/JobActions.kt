package com.circleci.idea.toolwindow.actions

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.job.JobDetailsService
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
    icon: javax.swing.Icon? = null
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
        action: suspend () -> Result<Unit>
    ) {
        val project = e.project ?: return

        // Show confirmation dialog if needed
        if (confirmMessage != null) {
            val result = Messages.showYesNoDialog(
                project,
                confirmMessage,
                "Confirm Action",
                Messages.getQuestionIcon()
            )
            if (result != Messages.YES) {
                return
            }
        }

        scope.launch {
            // Execute action
            val result = withContext(Dispatchers.IO) {
                action()
            }

            // Handle result
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    // Refresh tree to show updated state
                    project.getService(CircleCIToolWindowService::class.java).reloadTree()
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    Messages.showErrorDialog(
                        project,
                        "Failed to perform action: $error",
                        "Action Failed"
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
 * Action to open job details in the panel.
 */
class OpenJobDetailsAction : JobAction(
    "Open Job Details",
    "Open detailed view of this job",
    AllIcons.General.Information
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val jobNode = getJobNode(e) ?: return
        val job = jobNode.job

        scope.launch {
            val jobDetailsService = project.getService(JobDetailsService::class.java)
            jobDetailsService.selectAndFetchJobDetails(
                jobId = job.id,
                jobNumber = job.jobNumber,
                projectSlug = job.projectSlug
            )

            // Show job details panel in tool window service
            project.getService(CircleCIToolWindowService::class.java).showJobDetailsPanel()
        }
    }
}

/**
 * Action to rerun a job with SSH enabled.
 */
class RerunJobWithSshAction : JobAction(
    "Rerun with SSH",
    "Rerun this job with SSH access enabled",
    AllIcons.Actions.RestartDebugger
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
                    enableSsh = true
                )
            }
        )
    }

    override fun isEnabledForJob(job: JobNode): Boolean {
        // Can only rerun jobs that have completed (not currently running)
        return job.job.status in listOf("success", "failed", "canceled")
    }
}

/**
 * Action to cancel a running job.
 */
class CancelJobAction : JobAction(
    "Cancel Job",
    "Cancel this running job",
    AllIcons.Actions.Suspend
) {
    override fun actionPerformed(e: AnActionEvent) {
        val jobNode = getJobNode(e) ?: return
        val job = jobNode.job

        executeAction(
            e,
            "Cancel job '${job.name}'?",
            {
                val jobNumber = job.jobNumber
                if (jobNumber != null) {
                    CircleCIApiService.getInstance().cancelJob(job.projectSlug, jobNumber)
                } else {
                    Result.failure(IllegalStateException("Job number is not available"))
                }
            }
        )
    }

    override fun isEnabledForJob(job: JobNode): Boolean {
        // Can only cancel jobs that are running
        return job.job.status in listOf("running", "queued")
    }
}

/**
 * Action to copy job number to clipboard.
 */
class CopyJobNumberAction : JobAction(
    "Copy Job Number",
    "Copy job number to clipboard",
    AllIcons.Actions.Copy
) {
    override fun actionPerformed(e: AnActionEvent) {
        val jobNode = getJobNode(e) ?: return
        val job = jobNode.job
        val jobNumber = job.jobNumber

        if (jobNumber != null) {
            val stringSelection = StringSelection(jobNumber.toString())
            CopyPasteManager.getInstance().setContents(stringSelection)

            // Show a subtle notification
            Messages.showInfoMessage(
                e.project,
                "Job number $jobNumber copied to clipboard",
                "Copied"
            )
        } else {
            Messages.showErrorDialog(
                e.project,
                "Job number is not available for this job",
                "Copy Failed"
            )
        }
    }

    override fun isEnabledForJob(job: JobNode): Boolean {
        // Only enable if job has a number
        return job.job.jobNumber != null
    }
}

/**
 * Action to open job in CircleCI web browser.
 */
class OpenJobInBrowserAction : JobAction(
    "Open in Browser",
    "Open this job in CircleCI web interface",
    AllIcons.Ide.External_link_arrow
) {
    override fun actionPerformed(e: AnActionEvent) {
        val jobNode = getJobNode(e) ?: return
        val job = jobNode.job
        val jobNumber = job.jobNumber

        if (jobNumber != null) {
            // Construct CircleCI web URL for job
            // Format: https://app.circleci.com/pipelines/{vcs}/{org}/{project}/{job-number}
            // Since we have project_slug in format "vcs/org/project", we can use it
            val projectSlug = job.projectSlug
            val url = "https://app.circleci.com/pipelines/$projectSlug/jobs/$jobNumber"

            com.intellij.ide.BrowserUtil.browse(url)
        } else {
            Messages.showErrorDialog(
                e.project,
                "Cannot open job in browser: job number is not available",
                "Open Failed"
            )
        }
    }

    override fun isEnabledForJob(job: JobNode): Boolean {
        // Only enable if job has a number
        return job.job.jobNumber != null
    }
}

/**
 * Action to rerun the entire workflow from start (for jobs).
 */
class RerunWorkflowFromJobAction : JobAction(
    "Rerun Workflow",
    "Rerun the entire workflow from the beginning",
    AllIcons.Actions.Execute
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
                    fromFailed = false
                )
            }
        )
    }

    override fun isEnabledForJob(job: JobNode): Boolean {
        // Can rerun workflow if job is completed
        val workflowNode = getWorkflowNode(job)
        return workflowNode != null &&
               workflowNode.workflow.status in listOf("success", "failed", "canceled", "failing")
    }
}
