package com.circleci.idea.toolwindow.actions

import com.circleci.idea.api.ChangeService
import com.circleci.idea.api.CircleCIApiService
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

/**
 * Base class for workflow actions.
 */
abstract class WorkflowAction(
    text: String,
    description: String,
    icon: javax.swing.Icon? = null,
) : AnAction(text, description, icon), DumbAware {
    /**
     * Get the workflow node from the action event.
     */
    protected fun getWorkflowNode(e: AnActionEvent): WorkflowNode? {
        val project = e.project ?: return null
        val toolWindowService = project.getService(CircleCIToolWindowService::class.java)
        return toolWindowService.getSelectedNode() as? WorkflowNode
    }

    /**
     * Execute an API action with error handling and tree refresh, under
     * the IDE's progress indicator titled [progressTitle].
     */
    protected fun executeAction(
        e: AnActionEvent,
        confirmMessage: String?,
        progressTitle: String,
        action: suspend (String) -> Result<Unit>,
    ) {
        val project = e.project ?: return
        val workflowNode = getWorkflowNode(e) ?: return
        executeAction(project, workflowNode, confirmMessage, progressTitle, action)
    }

    /**
     * Execute an API action on [workflowNode], for when it's known without
     * the action event, such as after a popup closes.
     */
    protected fun executeAction(
        project: Project,
        workflowNode: WorkflowNode,
        confirmMessage: String?,
        progressTitle: String,
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

        ChangeService.getInstance(project).launch(progressTitle, { action(workflowId) }) { result ->
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
        val workflowNode = getWorkflowNode(e)
        e.presentation.isEnabled = workflowNode != null && isEnabledForWorkflow(workflowNode)
    }

    /**
     * Override to add custom enable logic based on workflow state.
     */
    protected open fun isEnabledForWorkflow(workflow: WorkflowNode): Boolean = true
}

/** The workflow's jobs, as far as they've loaded in the tree. */
private fun loadedJobs(workflow: WorkflowNode): List<Job> =
    workflow.children().asSequence().filterIsInstance<JobNode>().map { it.job }.toList()

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
            "Rerunning workflow '${workflowNode.workflow.name}'",
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
            "Rerunning workflow '${workflowNode.workflow.name}' from failed jobs",
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
        // Only build jobs run on an executor to SSH into.
        val jobs = loadedJobs(workflowNode).filter { it.isBuild }
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

        executeAction(
            project,
            workflowNode,
            confirmMessage,
            "Rerunning workflow '${workflowNode.workflow.name}' with SSH",
        ) { workflowId ->
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
            "Canceling workflow '${workflowNode.workflow.name}'",
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
 * Action to approve a workflow's job waiting on approval, so the workflow
 * carries on. With more than one waiting, it asks which.
 */
class ApproveWorkflowAction : WorkflowAction(
    "Approve",
    "Approve this workflow's job waiting on approval",
    AllIcons.Actions.Checked,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val workflowNode = getWorkflowNode(e) ?: return
        val jobs = awaitingApproval(workflowNode)
        if (jobs.size == 1) {
            approve(project, workflowNode, jobs.single())
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(jobs)
            .setTitle("Approve Job")
            .setRenderer(textListCellRenderer { it?.name })
            .setItemChosenCallback { job -> approve(project, workflowNode, job) }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }

    private fun approve(
        project: Project,
        workflowNode: WorkflowNode,
        job: Job,
    ) {
        executeAction(
            project,
            workflowNode,
            "Approve '${job.name}' in workflow '${workflowNode.workflow.name}'?",
            "Approving '${job.name}'",
        ) { workflowId ->
            // An approval job's ID is its approval request's.
            CircleCIApiService.getInstance().approveWorkflow(workflowId, job.id)
        }
    }

    /** Its loaded jobs waiting on approval, whose IDs the approvals need. */
    private fun awaitingApproval(workflow: WorkflowNode): List<Job> = loadedJobs(workflow).filter { it.awaitsApproval }

    override fun isEnabledForWorkflow(workflow: WorkflowNode): Boolean = awaitingApproval(workflow).isNotEmpty()
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
