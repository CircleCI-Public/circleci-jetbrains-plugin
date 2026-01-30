package com.circleci.idea.polling

import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.project.CircleCIProjectService
import com.circleci.idea.settings.CircleCISettings
import com.circleci.idea.toolwindow.CircleCIToolWindowService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Service for polling pipeline updates.
 * Uses adaptive polling intervals based on pipeline age:
 * - Fast polling (30s) for pipelines < 1 day old
 * - Slow polling (2m) for pipelines > 1 day old
 */
@Service(Service.Level.PROJECT)
class PipelinePollingService(private val project: Project) : Disposable {
    private val logger = CircleCILogger.getInstance()
    private val settings = CircleCISettings.getInstance()
    private val projectService = project.getService(CircleCIProjectService::class.java)
    private val toolWindowService = project.getService(CircleCIToolWindowService::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    private var lastPollTime: Instant? = null
    private var newestPipelineTime: Instant? = null

    init {
        logger.logLifecycleEvent("PipelinePollingService initialized")
    }

    /**
     * Start polling for pipeline updates.
     * Only starts if auto-refresh is enabled in settings.
     */
    fun startPolling() {
        if (!settings.autoRefreshEnabled) {
            logger.info("Auto-refresh is disabled, not starting polling")
            return
        }

        if (pollingJob?.isActive == true) {
            logger.info("Polling already active")
            return
        }

        logger.info("Starting pipeline polling")
        pollingJob =
            scope.launch {
                while (isActive) {
                    try {
                        // Only poll if tool window is visible
                        if (isToolWindowVisible()) {
                            pollPipelines()
                        } else {
                            logger.debug("Tool window not visible, skipping poll")
                        }

                        // Wait for next poll interval
                        val interval = calculatePollInterval()
                        logger.debug("Next poll in ${interval}ms")
                        delay(interval)
                    } catch (e: Exception) {
                        logger.error("Error in polling loop", e)
                        delay(settings.slowPollIntervalSeconds * 1000L) // Fall back to slow interval on error
                    }
                }
            }
    }

    /**
     * Stop polling for pipeline updates.
     */
    fun stopPolling() {
        logger.info("Stopping pipeline polling")
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Restart polling (stop and start).
     */
    fun restartPolling() {
        stopPolling()
        startPolling()
    }

    /**
     * Check if polling is currently active.
     */
    fun isPolling(): Boolean = pollingJob?.isActive == true

    /**
     * Poll pipelines for all selected projects.
     */
    private suspend fun pollPipelines() {
        val selectedProjects = projectService.getSelectedProjectObjects()
        if (selectedProjects.isEmpty()) {
            logger.debug("No selected projects, skipping poll")
            return
        }

        logger.debug("Polling pipelines for ${selectedProjects.size} projects")
        lastPollTime = Instant.now()

        // Refresh pipeline data for all loaded projects
        // This will re-fetch from API while preserving tree state via TreeStateManager
        toolWindowService.refreshPipelines()
    }

    /**
     * Calculate the next poll interval based on pipeline age.
     * Returns interval in milliseconds.
     */
    private fun calculatePollInterval(): Long {
        // If we haven't detected any pipelines yet, use fast polling
        val newestPipeline = newestPipelineTime
        if (newestPipeline == null) {
            logger.debug("No pipeline age detected, using fast poll interval")
            return settings.fastPollIntervalSeconds * 1000L
        }

        // Calculate age of newest pipeline
        val now = Instant.now()
        val ageInHours = ChronoUnit.HOURS.between(newestPipeline, now)

        // Use fast polling for pipelines less than 24 hours old
        return if (ageInHours < 24) {
            logger.debug("Newest pipeline is ${ageInHours}h old, using fast poll interval")
            settings.fastPollIntervalSeconds * 1000L
        } else {
            logger.debug("Newest pipeline is ${ageInHours}h old, using slow poll interval")
            settings.slowPollIntervalSeconds * 1000L
        }
    }

    /**
     * Update the newest pipeline time for adaptive polling.
     * Should be called after fetching pipelines.
     */
    fun updateNewestPipelineTime(pipelineCreatedAt: String) {
        try {
            val pipelineTime = Instant.parse(pipelineCreatedAt)
            val current = newestPipelineTime

            if (current == null || pipelineTime.isAfter(current)) {
                logger.debug("Updated newest pipeline time: $pipelineCreatedAt")
                newestPipelineTime = pipelineTime
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse pipeline timestamp: $pipelineCreatedAt", e)
        }
    }

    /**
     * Check if the CircleCI tool window is currently visible.
     */
    private fun isToolWindowVisible(): Boolean {
        return try {
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow("CircleCI")
            toolWindow?.isVisible == true
        } catch (e: Exception) {
            logger.warn("Failed to check tool window visibility", e)
            false
        }
    }

    override fun dispose() {
        logger.logLifecycleEvent("PipelinePollingService disposing")
        stopPolling()
        scope.cancel()
    }
}
