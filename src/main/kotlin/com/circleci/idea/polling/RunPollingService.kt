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
 * Service for polling run updates.
 * Uses adaptive polling intervals based on run age:
 * - Fast polling (30s) for runs < 1 day old
 * - Slow polling (2m) for runs > 1 day old
 */
@Service(Service.Level.PROJECT)
class RunPollingService(private val project: Project) : Disposable {
    private val logger = CircleCILogger.getInstance()
    private val settings = CircleCISettings.getInstance()
    private val projectService = project.getService(CircleCIProjectService::class.java)
    private val toolWindowService = project.getService(CircleCIToolWindowService::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    private var lastPollTime: Instant? = null
    private var newestRunTime: Instant? = null

    init {
        logger.logLifecycleEvent("RunPollingService initialized")
    }

    /**
     * Start polling for run updates.
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

        logger.info("Starting run polling")
        pollingJob =
            scope.launch {
                while (isActive) {
                    try {
                        // Only poll if tool window is visible
                        if (isToolWindowVisible()) {
                            pollRuns()
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
     * Stop polling for run updates.
     */
    fun stopPolling() {
        logger.info("Stopping run polling")
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
     * Poll runs for all selected projects.
     */
    private suspend fun pollRuns() {
        val selectedProjects = projectService.getSelectedProjectObjects()
        if (selectedProjects.isEmpty()) {
            logger.debug("No selected projects, skipping poll")
            return
        }

        logger.debug("Polling runs for ${selectedProjects.size} projects")
        lastPollTime = Instant.now()

        // Refresh run data for all loaded projects
        // This will re-fetch from API while preserving tree state via TreeStateManager
        toolWindowService.refreshRuns()
    }

    /**
     * Calculate the next poll interval based on run age.
     * Returns interval in milliseconds.
     */
    private fun calculatePollInterval(): Long {
        // If we haven't detected any runs yet, use fast polling
        val newestRun = newestRunTime
        if (newestRun == null) {
            logger.debug("No run age detected, using fast poll interval")
            return settings.fastPollIntervalSeconds * 1000L
        }

        // Calculate age of newest run
        val now = Instant.now()
        val ageInHours = ChronoUnit.HOURS.between(newestRun, now)

        // Use fast polling for runs less than 24 hours old
        return if (ageInHours < 24) {
            logger.debug("Newest run is ${ageInHours}h old, using fast poll interval")
            settings.fastPollIntervalSeconds * 1000L
        } else {
            logger.debug("Newest run is ${ageInHours}h old, using slow poll interval")
            settings.slowPollIntervalSeconds * 1000L
        }
    }

    /**
     * Update the newest run time for adaptive polling.
     * Should be called after fetching runs.
     */
    fun updateNewestRunTime(runCreatedAt: Instant) {
        val current = newestRunTime
        if (current == null || runCreatedAt.isAfter(current)) {
            logger.debug("Updated newest run time: $runCreatedAt")
            newestRunTime = runCreatedAt
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
        logger.logLifecycleEvent("RunPollingService disposing")
        stopPolling()
        scope.cancel()
    }
}
