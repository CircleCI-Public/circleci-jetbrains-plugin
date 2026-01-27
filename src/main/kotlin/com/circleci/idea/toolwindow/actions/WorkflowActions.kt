package com.circleci.idea.toolwindow.actions

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.toolwindow.CircleCIToolWindowService
import com.circleci.idea.toolwindow.tree.WorkflowNode
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
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
            val apiService = CircleCIApiService.getInstance()

            // Execute action
            val result =
                withContext(Dispatchers.IO) {
                    action(workflowId)
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
        // Can rerun workflows that are completed (success, failed, canceled)
        return workflow.workflow.status in listOf("success", "failed", "canceled", "failing")
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
        return workflow.workflow.status in listOf("failed", "failing")
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
        // Can only cancel workflows that are running or on_hold
        return workflow.workflow.status in listOf("running", "failing", "on_hold")
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
        return workflow.workflow.status == "on_hold"
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

        // Construct CircleCI web URL for workflow
        // Format: https://app.circleci.com/pipelines/{vcs}/{org}/{project}/{pipeline-number}/workflows/{workflow-id}
        val workflow = workflowNode.workflow
        val pipelineId = workflow.pipelineId

        // For now, use a simplified URL
        val url = "https://app.circleci.com/pipelines/workflows/${workflow.id}"

        com.intellij.ide.BrowserUtil.browse(url)
    }
}
