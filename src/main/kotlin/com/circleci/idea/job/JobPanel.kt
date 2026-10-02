package com.circleci.idea.job

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.run.elapsedSince
import com.circleci.idea.state.JobDetail
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.SimpleTabContent
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.defaultTabStyle
import org.jetbrains.jewel.ui.typography
import java.awt.BorderLayout
import javax.swing.JComponent

/**
 * A job's page, in tabs: its steps beside the selected step's output, its
 * tests, its artifacts, and its resource usage. The IDE's toolbar of job
 * actions sits above a Compose view of the rest.
 *
 * The job is re-read every few seconds until it ends, so new steps appear
 * and statuses change as it runs.
 */
class JobPanel(
    private val project: Project,
    private val file: JobVirtualFile,
) : JBPanel<JobPanel>(BorderLayout()), Disposable {
    private val logger = CircleCILogger.getInstance()
    private val service = JobDetailsService.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val ref: JobRef = file.ref

    /** The job as last read, or null until the first read arrives. Read off the EDT by the output stream. */
    @Volatile
    var detail: JobDetail? = null
        private set

    private val jobState = MutableStateFlow<JobDetail?>(null)
    private val loadErrorState = MutableStateFlow<String?>(null)
    private val _tab = MutableStateFlow(JobTab.STEPS)
    val tab: StateFlow<JobTab> = _tab.asStateFlow()

    private val stepsTab = StepsTab(project, ref, scope, service, this) { detail }
    private val testsTab = TestsTab(project, this)
    private val artifactsTab = ArtifactsTab(project, ref, scope)
    private val resourceUsageTab = ResourceUsageTab(ref, scope, service)

    private val page: JComponent = JewelComposePanel { Page() }

    private var pollJob: Job? = null

    val preferredFocusedComponent: JComponent
        get() = page

    /** The execution of the selected step, or of the first one. */
    val selectedExecution: Int
        get() = stepsTab.selected.value?.execution ?: 0

    init {
        val toolbar =
            ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, JobActions.group(this), true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)
        add(page, BorderLayout.CENTER)
        refresh()
    }

    /**
     * Re-read the job, and keep re-reading it until it ends.
     */
    fun refresh() {
        pollJob?.cancel()
        pollJob =
            scope.launch {
                var wasActive: Boolean? = null
                while (isActive) {
                    val result = service.fetchJob(ref.jobId)
                    val job = result.getOrNull()
                    if (job == null) {
                        logger.warn("Failed to load job ${ref.jobId}: ${result.exceptionOrNull()?.message}")
                        loadErrorState.value = "Failed to load job: ${result.exceptionOrNull()?.message}"
                        return@launch
                    }
                    show(job)

                    // Tests, artifacts and resource usage are only complete once the job ends.
                    if (wasActive != false && !job.status.isActive) {
                        loadEndOfJobTabs()
                    }
                    if (!job.status.isActive) return@launch
                    wasActive = true
                    delay(JOB_POLL_INTERVAL_MS)
                }
            }
    }

    private fun show(job: JobDetail) {
        detail = job
        jobState.value = job
        loadErrorState.value = null

        if (file.status != job.status) {
            file.status = job.status
            FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(file)
        }

        stepsTab.show(job.executions)
    }

    private fun loadEndOfJobTabs() {
        scope.launch {
            service.fetchTests(ref.jobId).fold(
                onSuccess = { testsTab.setTests(it) },
                onFailure = { testsTab.showError(it.message ?: "Unknown error") },
            )
        }
        artifactsTab.load()
        resourceUsageTab.load()
    }

    @Composable
    private fun Page() {
        val job by jobState.collectAsState()
        val loadError by loadErrorState.collectAsState()
        val tab by tab.collectAsState()
        Column(Modifier.fillMaxSize()) {
            Header(job, loadError)
            val tabs =
                JobTab.entries.map { entry ->
                    TabData.Default(
                        selected = entry == tab,
                        content = { state -> SimpleTabContent(entry.title, state) },
                        closable = false,
                        onClick = { _tab.value = entry },
                    )
                }
            TabStrip(tabs, JewelTheme.defaultTabStyle, Modifier.fillMaxWidth())
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    JobTab.STEPS -> stepsTab.View()
                    JobTab.TESTS -> testsTab.View()
                    JobTab.ARTIFACTS -> artifactsTab.View()
                    JobTab.RESOURCE_USAGE -> resourceUsageTab.View()
                }
            }
        }
    }

    @Composable
    private fun Header(
        job: JobDetail?,
        loadError: String?,
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(ref.name, style = JewelTheme.typography.h3TextStyle)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                job?.let { Icon(statusIconKey(it.status), it.status.label, Modifier.size(STATUS_ICON.dp)) }
                Text(loadError ?: summary(job), color = JewelTheme.globalColors.text.info)
            }
        }
    }

    /** "#123 · Running · 3m 20s · build (run #45)", as much of it as is known. */
    private fun summary(job: JobDetail?): String {
        val parts = mutableListOf<String>()
        ref.number?.let { parts.add("#$it") }
        if (job == null) {
            parts.add("Loading...")
        } else {
            parts.add(job.status.label)
            elapsedSince(job.startedAt, job.endedAt)?.let { parts.add(it) }
        }
        ref.workflowName?.let { workflow -> parts.add(ref.runNumber?.let { "$workflow (run #$it)" } ?: workflow) }
        return parts.joinToString(" · ")
    }

    override fun dispose() {
        scope.cancel()
    }

    private companion object {
        /** How often a running job is re-read for new steps and statuses. */
        const val JOB_POLL_INTERVAL_MS = 5_000L
        const val STATUS_ICON = 16
    }
}

/** The job page's tabs, in order. */
enum class JobTab(val title: String) {
    STEPS("Steps"),
    TESTS("Tests"),
    ARTIFACTS("Artifacts"),
    RESOURCE_USAGE("Resource Usage"),
}
