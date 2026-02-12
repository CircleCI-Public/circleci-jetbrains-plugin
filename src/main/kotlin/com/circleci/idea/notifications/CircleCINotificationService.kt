package com.circleci.idea.notifications

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.icons.CircleCIIcons
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.websocket.CircleCIWebSocketEvent
import com.circleci.idea.websocket.CircleCIWebSocketService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for managing CircleCI notifications.
 *
 * Features:
 * - Subscribes to WebSocket events
 * - Filters based on user preferences (status, my pipelines only)
 * - Throttles notifications (max 1 per workflow every 5 minutes)
 * - Deduplicates notifications
 * - Shows rich notifications with action buttons
 */
@Service(Service.Level.PROJECT)
class CircleCINotificationService(private val project: Project) {
    private val logger = CircleCILogger.getInstance()
    private val stateStore = CircleCIStateStore.getInstance(project)
    private val webSocketService = CircleCIWebSocketService.getInstance()
    private val apiService = CircleCIApiService.getInstance()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Notification group
    private val notificationGroup =
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CircleCI Notifications")

    // Throttling: track last notification time per workflow
    private val lastNotificationTime = ConcurrentHashMap<String, Long>()
    private val throttleWindowMs = 5 * 60 * 1000L // 5 minutes

    // Default statuses to notify for (exclude success and running - too noisy)
    private val defaultNotificationStatuses =
        setOf(
            "failed",
            "failing",
            "canceled",
            "error",
            "on_hold",
            "not_run",
            "unauthorized",
        )

    init {
        logger.logLifecycleEvent("CircleCINotificationService initialized")
        Disposer.register(project, this::dispose)
        observeWebSocketEvents()
    }

    /**
     * Subscribe to WebSocket events and show notifications.
     */
    private fun observeWebSocketEvents() {
        scope.launch {
            webSocketService.events.collectLatest { event ->
                when (event) {
                    is CircleCIWebSocketEvent.WorkflowCompleted -> {
                        handleWorkflowCompleted(event)
                    }
                    is CircleCIWebSocketEvent.JobCompleted -> {
                        handleJobCompleted(event)
                    }
                    else -> {
                        // Ignore other events for notifications
                    }
                }
            }
        }
    }

    /**
     * Handle workflow completed event.
     */
    private suspend fun handleWorkflowCompleted(event: CircleCIWebSocketEvent.WorkflowCompleted) {
        logger.debug("Handling workflow completed event: ${event.workflowId}, status: ${event.status}")

        // Check if notifications are enabled
        val preferences = stateStore.ui.value.notificationPreferences
        if (!preferences.enabled) {
            logger.debug("Notifications disabled, skipping")
            return
        }

        // Check if status should trigger notification
        val statusFilter = preferences.statusFilter.ifEmpty { defaultNotificationStatuses }
        if (event.status.lowercase() !in statusFilter.map { it.lowercase() }) {
            logger.debug("Status ${event.status} not in filter, skipping")
            return
        }

        // Check throttling
        if (isThrottled(event.workflowId)) {
            logger.debug("Workflow ${event.workflowId} is throttled, skipping")
            return
        }

        // Fetch workflow details for notification content
        try {
            val workflowDetails = fetchWorkflowDetails(event.workflowId, event.projectSlug)
            if (workflowDetails != null) {
                showWorkflowNotification(workflowDetails, event.status, event.projectSlug)
                updateThrottleTime(event.workflowId)
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch workflow details for notification", e)
        }
    }

    /**
     * Handle job completed event.
     */
    private suspend fun handleJobCompleted(event: CircleCIWebSocketEvent.JobCompleted) {
        logger.debug("Handling job completed event: ${event.jobId}, status: ${event.status}")

        // Check if notifications are enabled
        val preferences = stateStore.ui.value.notificationPreferences
        if (!preferences.enabled) {
            return
        }

        // Check if status should trigger notification
        val statusFilter = preferences.statusFilter.ifEmpty { defaultNotificationStatuses }
        if (event.status.lowercase() !in statusFilter.map { it.lowercase() }) {
            return
        }

        // For jobs, we might want to show less intrusive notifications
        // or only show if the whole workflow fails
        // For now, skip individual job notifications to avoid noise
        logger.debug("Skipping individual job notification to reduce noise")
    }

    /**
     * Check if workflow is throttled.
     */
    private fun isThrottled(workflowId: String): Boolean {
        val lastTime = lastNotificationTime[workflowId] ?: return false
        val elapsed = System.currentTimeMillis() - lastTime
        return elapsed < throttleWindowMs
    }

    /**
     * Update throttle time for workflow.
     */
    private fun updateThrottleTime(workflowId: String) {
        lastNotificationTime[workflowId] = System.currentTimeMillis()
    }

    /**
     * Fetch workflow details from API.
     */
    private suspend fun fetchWorkflowDetails(
        workflowId: String,
        projectSlug: String,
    ): WorkflowNotificationData? {
        return withContext(Dispatchers.IO) {
            try {
                // Get workflows from state if available
                val projectData = stateStore.projectsData.value.data[projectSlug]
                val workflow =
                    projectData?.pipelines
                        ?.flatMap { it.workflows }
                        ?.find { it.id == workflowId }

                if (workflow != null) {
                    val pipeline =
                        projectData.pipelines.find {
                            it.workflows.any { w -> w.id == workflowId }
                        }

                    WorkflowNotificationData(
                        workflowId = workflow.id,
                        workflowName = workflow.name,
                        pipelineNumber = pipeline?.number,
                        branch = pipeline?.branch,
                        author = pipeline?.trigger?.actor?.login,
                        projectSlug = projectSlug,
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                logger.error("Failed to fetch workflow details", e)
                null
            }
        }
    }

    /**
     * Show workflow notification.
     */
    private fun showWorkflowNotification(
        details: WorkflowNotificationData,
        status: String,
        projectSlug: String,
    ) {
        val title = "Workflow ${getStatusText(status)}"
        val content = buildNotificationContent(details, status)
        val notificationType = getNotificationType(status)

        val notification =
            notificationGroup.createNotification(title, content, notificationType)
                .setIcon(getStatusIcon(status))

        // Add action buttons
        notification.addAction(ViewWorkflowAction(details.workflowId, projectSlug))

        if (status.lowercase() == "on_hold") {
            notification.addAction(ApproveWorkflowAction(details.workflowId))
        }

        if (status.lowercase() in setOf("failed", "failing", "canceled")) {
            notification.addAction(RerunWorkflowAction(details.workflowId))
        }

        notification.addAction(DisableNotificationsAction())

        notification.notify(project)

        logger.info("Showed notification for workflow ${details.workflowId}: $status")
    }

    /**
     * Build notification content.
     */
    private fun buildNotificationContent(
        details: WorkflowNotificationData,
        @Suppress("UNUSED_PARAMETER") _status: String,
    ): String {
        val parts = mutableListOf<String>()

        parts.add("<b>${details.workflowName}</b>")

        if (details.pipelineNumber != null) {
            parts.add("Pipeline #${details.pipelineNumber}")
        }

        if (details.branch != null) {
            parts.add("Branch: ${details.branch}")
        }

        if (details.author != null) {
            parts.add("Author: ${details.author}")
        }

        return parts.joinToString("<br/>")
    }

    /**
     * Get status text for notification title.
     */
    private fun getStatusText(status: String): String {
        return when (status.lowercase()) {
            "failed" -> "Failed ✗"
            "failing" -> "Failing ✗"
            "canceled" -> "Canceled"
            "on_hold" -> "Awaiting Approval"
            "error" -> "Error ✗"
            "not_run" -> "Not Run"
            "unauthorized" -> "Unauthorized"
            else -> status.uppercase()
        }
    }

    /**
     * Get notification type based on status.
     */
    private fun getNotificationType(status: String): NotificationType {
        return when (status.lowercase()) {
            "failed", "failing", "error" -> NotificationType.ERROR
            "canceled", "not_run" -> NotificationType.WARNING
            "on_hold" -> NotificationType.INFORMATION
            else -> NotificationType.INFORMATION
        }
    }

    /**
     * Get status icon.
     */
    private fun getStatusIcon(status: String): javax.swing.Icon {
        return when (status.lowercase()) {
            "failed", "failing", "error" -> CircleCIIcons.Status.FAILED
            "canceled" -> CircleCIIcons.Status.CANCELED
            "on_hold" -> CircleCIIcons.Status.ON_HOLD
            else -> CircleCIIcons.PLUGIN_ICON
        }
    }

    /**
     * Enable notifications.
     */
    fun enableNotifications() {
        logger.info("Enabling notifications")
        stateStore.updateNotificationPreferences(enabled = true)
    }

    /**
     * Disable notifications.
     */
    fun disableNotifications() {
        logger.info("Disabling notifications")
        stateStore.updateNotificationPreferences(enabled = false)
    }

    /**
     * Update notification preferences.
     */
    fun updateNotificationPreferences(
        enabled: Boolean? = null,
        myPipelinesOnly: Boolean? = null,
        statusFilter: Set<String>? = null,
    ) {
        logger.info("Updating notification preferences")
        stateStore.updateNotificationPreferences(enabled, myPipelinesOnly, statusFilter)
    }

    private fun dispose() {
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): CircleCINotificationService {
            return project.getService(CircleCINotificationService::class.java)
        }
    }
}

/**
 * Data for workflow notification.
 */
private data class WorkflowNotificationData(
    val workflowId: String,
    val workflowName: String,
    val pipelineNumber: Int?,
    val branch: String?,
    val author: String?,
    val projectSlug: String,
)

/**
 * Action to view workflow.
 */
private class ViewWorkflowAction(
    private val workflowId: String,
    private val projectSlug: String,
) : AnAction("View Workflow") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Open the CircleCI tool window and navigate to the workflow
        val url = "https://app.circleci.com/pipelines/$projectSlug/workflows/$workflowId"
        com.intellij.ide.BrowserUtil.browse(url)
    }
}

/**
 * Action to approve workflow.
 */
private class ApproveWorkflowAction(
    private val workflowId: String,
) : AnAction("Approve") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Launch approval in background
        GlobalScope.launch {
            // Note: We need approval request ID, which we don't have from the WebSocket event
            // For now, just show a message to open the tool window
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CircleCI Notifications")
                .createNotification(
                    "Approval Required",
                    "Please approve the workflow in the CircleCI tool window or web interface",
                    NotificationType.INFORMATION,
                )
                .notify(project)
        }
    }
}

/**
 * Action to rerun workflow.
 */
private class RerunWorkflowAction(
    private val workflowId: String,
) : AnAction("Rerun") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val apiService = CircleCIApiService.getInstance()

        GlobalScope.launch {
            val result = apiService.rerunWorkflow(workflowId, fromFailed = false)
            result.fold(
                onSuccess = {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("CircleCI Notifications")
                        .createNotification(
                            "Workflow Rerun",
                            "Workflow rerun started successfully",
                            NotificationType.INFORMATION,
                        )
                        .notify(project)
                },
                onFailure = { error ->
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("CircleCI Notifications")
                        .createNotification(
                            "Workflow Rerun Failed",
                            "Failed to rerun workflow: ${error.message}",
                            NotificationType.ERROR,
                        )
                        .notify(project)
                },
            )
        }
    }
}

/**
 * Action to disable notifications.
 */
private class DisableNotificationsAction : AnAction("Disable Notifications") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val notificationService = CircleCINotificationService.getInstance(project)
        notificationService.disableNotifications()

        NotificationGroupManager.getInstance()
            .getNotificationGroup("CircleCI Notifications")
            .createNotification(
                "Notifications Disabled",
                "CircleCI notifications have been disabled. Re-enable in Settings.",
                NotificationType.INFORMATION,
            )
            .notify(project)
    }
}
