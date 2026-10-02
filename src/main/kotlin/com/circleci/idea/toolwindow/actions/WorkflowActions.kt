package com.circleci.idea.toolwindow.actions

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.run.RunStatus
import com.circleci.idea.run.RunWebUrls
import com.circleci.idea.state.Job
import com.circleci.idea.toolwindow.CircleCIToolWindowService
import com.circleci.idea.toolwindow.tree.JobNode
import com.circleci.idea.toolwindow.tree.RunNode
import com.circleci.idea.toolwindow.tree.WorkflowNode
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Base class for workflow actions.
 */
abstract class WorkflowAction(
    text: String,
    description: String,
    icon: javax.swing.Icon? = null,
) : AnAction(text, description, icon), DumbAware {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Get the workflow node from the action event.
     */
    protected fun getWorkflowNode(e: AnActionEvent): WorkflowNode? {
        val project = e.project ?: return null
        val toolWindowService = project.getService(CircleCIToolWindowService::class.java)
        return toolWindowService.getSelectedNode() as? WorkflowNode
    }

    /**
     * Execute an API action with error handling and tree refresh.
     */
    protected fun executeAction(
        e: AnActionEvent,
        confirmMessage: String?,
        action: suspend (String) -> Result<Unit>,
    ) {
        val project = e.project ?: return
        val workflowNode = getWorkflowNode(e) ?: return
        executeAction(project, workflowNode, confirmMessage, action)
    }

    /**
     * Execute an API action on [workflowNode], for when it's known without
     * the action event, such as after a popup closes.
     */
    protected fun executeAction(
        project: Project,
        workflowNode: WorkflowNode,
        confirmMessage: String?,
        action: suspend (String) -> Result<Unit>,
    ) {
        val workflowId = workflowNode.workflow.id

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
                    action(workflowId)
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
        val workflowNode = getWorkflowNode(e)
        e.presentation.isEnabled = workflowNode != null && isEnabledForWorkflow(workflowNode)
    }

    /**
     * Override to add custom enable logic based on workflow state.
     */
    protected open fun isEnabledForWorkflow(workflow: WorkflowNode): Boolean = true
}

/**
 * Action to rerun a workflow from the start.
 */
class RerunWorkflowAction : WorkflowAction(
    "Rerun from Start",
    "Rerun this workflow from the beginning",
    AllIcons.Actions.Execute,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val workflowNode = getWorkflowNode(e) ?: return
        executeAction(
            e,
            "Rerun workflow '${workflowNode.workflow.name}' from start?",
            { workflowId ->
                CircleCIApiService.getInstance().rerunWorkflow(workflowId, fromFailed = false)
            },
        )
    }

    override fun isEnabledForWorkflow(workflow: WorkflowNode): Boolean {
        return workflow.workflow.status.isRerunnable
    }
}

/**
 * Action to rerun a workflow from failed jobs.
 */
class RerunWorkflowFromFailedAction : WorkflowAction(
    "Rerun from Failed",
    "Rerun this workflow from failed jobs",
    AllIcons.Actions.Restart,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val workflowNode = getWorkflowNode(e) ?: return
        executeAction(
            e,
            "Rerun workflow '${workflowNode.workflow.name}' from failed jobs?",
            { workflowId ->
                CircleCIApiService.getInstance().rerunWorkflow(workflowId, fromFailed = true)
            },
        )
    }

    override fun isEnabledForWorkflow(workflow: WorkflowNode): Boolean {
        // Can only rerun from failed if workflow has failed
        return workflow.workflow.status.isFailure
    }
}

/**
 * Action to rerun a workflow with SSH enabled.
 */
class RerunWorkflowWithSshAction : WorkflowAction(
    "Rerun with SSH",
    "Rerun this workflow with SSH access enabled for a specific job",
    AllIcons.Actions.RestartDebugger,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val workflowNode = getWorkflowNode(e) ?: return
        val jobs = workflowNode.children().asSequence().filterIsInstance<JobNode>().map { it.job }.toList()
        if (jobs.isEmpty()) {
            Messages.showErrorDialog(
                project,
                "No jobs found in this workflow. Load the workflow details first.",
                "No Jobs Available",
            )
            return
        }

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(jobs)
            .setTitle("Enable SSH for Job")
            .setRenderer(textListCellRenderer { it?.name })
            .setItemChosenCallback { job -> rerunWithSsh(project, workflowNode, job) }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }

    private fun rerunWithSsh(
        project: Project,
        workflowNode: WorkflowNode,
        job: Job,
    ) {
        val confirmMessage =
            "Rerun workflow '${workflowNode.workflow.name}' " +
                "with SSH enabled for job '${job.name}'?\n\n" +
                "This will rerun the entire workflow with SSH access enabled for this specific job."

        executeAction(project, workflowNode, confirmMessage) { workflowId ->
            CircleCIApiService.getInstance().rerunWorkflow(
                workflowId,
                fromFailed = false,
                enableSsh = true,
                jobs = listOf(job.id),
            )
        }
    }

    override fun isEnabledForWorkflow(workflow: WorkflowNode): Boolean {
        return workflow.workflow.status.isRerunnable
    }
}

/**
 * Action to cancel a running workflow.
 */
class CancelWorkflowAction : WorkflowAction(
    "Cancel Workflow",
    "Cancel this running workflow",
    AllIcons.Actions.Suspend,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val workflowNode = getWorkflowNode(e) ?: return
        executeAction(
            e,
            "Cancel workflow '${workflowNode.workflow.name}'?\n\nThis will stop all running jobs.",
            { workflowId ->
                CircleCIApiService.getInstance().cancelWorkflow(workflowId)
            },
        )
    }

    override fun isEnabledForWorkflow(workflow: WorkflowNode): Boolean {
        return workflow.workflow.status.isCancelable
    }
}

/**
 * Action to approve a workflow (for workflows waiting on manual approval).
 */
class ApproveWorkflowAction : WorkflowAction(
    "Approve Workflow",
    "Approve this workflow to continue",
    AllIcons.Actions.Checked,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val workflowNode = getWorkflowNode(e) ?: return

        // Get approval request ID from user
        val approvalRequestId =
            Messages.showInputDialog(
                project,
                "Enter the approval request ID:",
                "Approve Workflow",
                Messages.getQuestionIcon(),
            )

        if (approvalRequestId.isNullOrBlank()) {
            return
        }

        executeAction(
            e,
            "Approve workflow '${workflowNode.workflow.name}'?",
            { workflowId ->
                CircleCIApiService.getInstance().approveWorkflow(workflowId, approvalRequestId)
            },
        )
    }

    override fun isEnabledForWorkflow(workflow: WorkflowNode): Boolean {
        // Can only approve workflows that are on_hold
        return workflow.workflow.status == RunStatus.ON_HOLD
    }
}

/**
 * Action to open workflow in browser.
 */
class OpenWorkflowInBrowserAction : WorkflowAction(
    "Open in Browser",
    "Open this workflow in CircleCI web interface",
    AllIcons.Ide.External_link_arrow,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val workflowNode = getWorkflowNode(e) ?: return

        val url = RunWebUrls.workflow(workflowNode.workflow)
        if (url == null) {
            Messages.showErrorDialog(project, "Cannot open workflow in browser: its run is unknown", "Open Failed")
            return
        }

        com.intellij.ide.BrowserUtil.browse(url)
    }
}

/**
 * Action to open a run in the browser.
 */
class OpenRunInBrowserAction :
    AnAction(
        "Open in Browser",
        "Open this run in CircleCI web interface",
        AllIcons.Ide.External_link_arrow,
    ),
    DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        runUrl(e)?.let { com.intellij.ide.BrowserUtil.browse(it) }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = runUrl(e) != null
    }

    private fun runUrl(e: AnActionEvent): String? {
        val project = e.project ?: return null
        val runNode = project.getService(CircleCIToolWindowService::class.java).getSelectedNode() as? RunNode
        return runNode?.let { RunWebUrls.run(it.run) }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
