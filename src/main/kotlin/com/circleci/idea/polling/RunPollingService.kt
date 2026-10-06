package com.circleci.idea.polling

import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.project.CircleCIProjectService
import com.circleci.idea.run.RunScope
import com.circleci.idea.run.RunStatus
import com.circleci.idea.settings.CircleCISettings
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.state.Run
import com.circleci.idea.toolwindow.CircleCIToolWindowService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import kotlin.math.min

/**
 * Service for polling run updates while the tool window is showing.
 *
 * It polls at the fast interval while a run listed is in progress (see
 * [isInProgress]), and at the slow one otherwise, or while the IDE isn't the
 * active app. Failed polls
 * back off, doubling the slow interval each time up to [MAX_BACKOFF_MS].
 * Showing the tool window, or switching back to the IDE, polls straight away
 * once the fast interval has passed since the last poll.
 */
@Service(Service.Level.PROJECT)
class RunPollingService(private val project: Project) : Disposable {
    private val logger = CircleCILogger.getInstance()
    private val settings = CircleCISettings.getInstance()
    private val projectService = project.getService(CircleCIProjectService::class.java)
    private val toolWindowService = project.getService(CircleCIToolWindowService::class.java)
    private val stateStore = CircleCIStateStore.getInstance(project)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    // Cuts the wait for the next poll short.
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var lastPollMs = 0L
    private var failures = 0

    init {
        logger.logLifecycleEvent("RunPollingService initialized")
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) = wakeIfDue()
            },
        )
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    if (toolWindow.id == TOOL_WINDOW_ID) wakeIfDue()
                }
            },
        )
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
                    val interval =
                        try {
                            if (isToolWindowVisible()) pollRuns() else slowIntervalMs()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (
                            @Suppress("TooGenericExceptionCaught") e: Exception,
                        ) {
                            logger.error("Error in polling loop", e)
                            slowIntervalMs()
                        }
                    logger.debug("Next poll in ${interval}ms")
                    withTimeoutOrNull(interval) { wake.receive() }
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
     * Poll the runs the tool window lists, giving how long to wait for the next poll.
     */
    private suspend fun pollRuns(): Long {
        if (projectService.getSelectedProject() == null && stateStore.filters.value.scope != RunScope.MY_RUNS) {
            logger.debug("No project to list runs for, skipping poll")
            return slowIntervalMs()
        }

        lastPollMs = System.currentTimeMillis()

        // Re-fetch from the API, keeping the tree's state
        val result = toolWindowService.pollRuns() ?: return slowIntervalMs()
        return result.fold(
            onSuccess = { runs ->
                failures = 0
                val inProgress = isInProgress(runs, Instant.now())
                if (inProgress && ApplicationManager.getApplication().isActive) {
                    fastIntervalMs()
                } else {
                    slowIntervalMs()
                }
            },
            onFailure = {
                failures++
                val backoff = slowIntervalMs() shl min(failures - 1, MAX_BACKOFF_DOUBLINGS)
                min(backoff, MAX_BACKOFF_MS)
            },
        )
    }

    private fun wakeIfDue() {
        if (System.currentTimeMillis() - lastPollMs >= fastIntervalMs()) wake.trySend(Unit)
    }

    private fun fastIntervalMs(): Long = settings.fastPollIntervalSeconds * MS_PER_SECOND

    private fun slowIntervalMs(): Long = settings.slowPollIntervalSeconds * MS_PER_SECOND

    /**
     * Check if the CircleCI tool window is currently visible.
     */
    private fun isToolWindowVisible(): Boolean {
        return try {
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID)
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

    private companion object {
        const val TOOL_WINDOW_ID = "CircleCI"
        const val MS_PER_SECOND = 1000L
        const val MAX_BACKOFF_MS = 15 * 60 * 1000L

        // Enough to reach the cap from any slow interval, without overflowing the shift.
        const val MAX_BACKOFF_DOUBLINGS = 10
    }
}

// Started runs, whose status can change any moment.
private val RUNNING = setOf(RunStatus.RUNNING, RunStatus.FAILING, RunStatus.ERRORING, RunStatus.CANCELING)

// Created or queued runs count as in progress for this long; one stuck longer waits on something else.
private val WAITING_TO_START = Duration.ofHours(1)

/**
 * Whether [runs] are worth polling for at the fast interval: one has
 * started, or was created recently and is waiting to start. A run on hold
 * (for an approval, often for days) or queued for long doesn't count.
 */
internal fun isInProgress(
    runs: List<Run>,
    now: Instant,
): Boolean =
    runs.any { run ->
        when (run.status) {
            in RUNNING -> true
            RunStatus.CREATED, RunStatus.QUEUED ->
                run.createdAt == null || Duration.between(run.createdAt, now) < WAITING_TO_START
            else -> false
        }
    }
