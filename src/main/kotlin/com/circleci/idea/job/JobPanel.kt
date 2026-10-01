package com.circleci.idea.job

import com.circleci.idea.icons.CircleCIIcons
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.run.RunStatus
import com.circleci.idea.run.elapsedSince
import com.circleci.idea.state.JobDetail
import com.circleci.idea.state.JobExecution
import com.circleci.idea.state.Step
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/** A step, by the execution it ran in and its number. */
data class StepKey(val execution: Int, val num: Int)

/**
 * A job's page, in tabs: its steps (by parallel execution, when there's
 * more than one) beside the selected step's output, streamed while it runs;
 * its tests; and its artifacts.
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

    private val titleLabel = JBLabel(ref.name)
    private val summaryLabel = JBLabel()

    private val stepsRoot = DefaultMutableTreeNode()
    private val stepsModel = DefaultTreeModel(stepsRoot)
    private val stepsTree = Tree(stepsModel)

    private val console: ConsoleView =
        TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console

    private val testsPanel = TestsPanel(project, this)
    private val artifactsPanel = ArtifactsPanel(project, ref, scope)

    private var pollJob: Job? = null
    private var streamJob: Job? = null
    private var streamingStep: StepKey? = null

    // Pick a step to show once, when the job first loads; after that the
    // selection is the user's.
    private var autoSelected = false

    val preferredFocusedComponent: JComponent
        get() = stepsTree

    /** The execution of the selected step, or of the first one. */
    val selectedExecution: Int
        get() = selectedStep()?.execution ?: 0

    init {
        Disposer.register(this, console)
        add(createHeader(), BorderLayout.NORTH)
        add(createContent(), BorderLayout.CENTER)
        console.print("Select a step to see its output.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
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
                        summaryLabel.text = "Failed to load job: ${result.exceptionOrNull()?.message}"
                        return@launch
                    }
                    show(job)

                    // Tests and artifacts are only complete once the job ends.
                    if (wasActive != false && !job.status.isActive) {
                        loadTestsAndArtifacts()
                    }
                    if (!job.status.isActive) return@launch
                    wasActive = true
                    delay(JOB_POLL_INTERVAL_MS)
                }
            }
    }

    private fun createHeader(): JComponent {
        val toolbar =
            ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, JobActions.group(this), true)
        toolbar.targetComponent = this

        titleLabel.font = JBUI.Fonts.label().biggerOn(2f).asBold()
        val labels =
            JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(4, 8)
                add(titleLabel)
                add(summaryLabel)
            }
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(labels, BorderLayout.CENTER)
        }
    }

    private fun createContent(): JComponent {
        stepsTree.isRootVisible = false
        stepsTree.showsRootHandles = true
        stepsTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        stepsTree.cellRenderer = StepTreeCellRenderer()
        stepsTree.emptyText.text = "Loading steps..."
        stepsTree.addTreeSelectionListener { selectedStep()?.let { streamStep(it) } }

        val steps =
            OnePixelSplitter(false, STEPS_PROPORTION).apply {
                firstComponent = JBScrollPane(stepsTree)
                secondComponent = console.component
            }

        return JBTabbedPane().apply {
            addTab("Steps", steps)
            addTab("Tests", testsPanel)
            addTab("Artifacts", artifactsPanel)
        }
    }

    private fun show(job: JobDetail) {
        detail = job

        val parts = mutableListOf<String>()
        ref.number?.let { parts.add("#$it") }
        parts.add(job.status.label)
        elapsedSince(job.startedAt, job.endedAt)?.let { parts.add(it) }
        ref.workflowName?.let { workflow -> parts.add(ref.runNumber?.let { "$workflow (run #$it)" } ?: workflow) }
        summaryLabel.text = parts.joinToString(" · ")
        summaryLabel.icon = CircleCIIcons.getStatusIcon(job.status)

        if (file.status != job.status) {
            file.status = job.status
            FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(file)
        }

        updateSteps(job.executions)
    }

    private fun updateSteps(executions: List<JobExecution>) {
        val selected = selectedStep()
        stepsRoot.removeAllChildren()
        if (executions.size == 1) {
            executions[0].steps.forEach { stepsRoot.add(DefaultMutableTreeNode(StepNode(0, it))) }
        } else {
            for (execution in executions) {
                val executionNode = DefaultMutableTreeNode(ExecutionNode(execution))
                execution.steps.forEach { executionNode.add(DefaultMutableTreeNode(StepNode(execution.index, it))) }
                stepsRoot.add(executionNode)
            }
        }
        stepsModel.reload()
        TreeUtil.expandAll(stepsTree)
        stepsTree.emptyText.text = "No steps yet"

        val toSelect = selected ?: if (autoSelected) null else stepToShow(executions)
        autoSelected = autoSelected || toSelect != null
        toSelect?.let { key -> findStepPath(key)?.let { TreeUtil.selectPath(stepsTree, it) } }
    }

    /** The step worth opening on: the first that failed, or else the one running now. */
    private fun stepToShow(executions: List<JobExecution>): StepKey? {
        val steps = executions.flatMap { execution -> execution.steps.map { StepKey(execution.index, it.num) to it } }
        val step =
            steps.firstOrNull { (_, step) -> step.status.isFailure }
                ?: steps.lastOrNull { (_, step) -> step.status.isActive }
        return step?.first
    }

    private fun streamStep(key: StepKey) {
        // Rebuilding the tree on each poll reselects the step being shown.
        if (key == streamingStep) return
        streamingStep = key
        streamJob?.cancel()
        console.clear()

        val decoder = AnsiEscapeDecoder()
        streamJob =
            scope.launch {
                var printedAny = false
                val isStepActive = { findStep(key)?.status?.isActive ?: false }
                service.stepOutput(ref.jobId, key.execution, key.num, isStepActive).events()
                    .flowOn(Dispatchers.IO)
                    .collect { event ->
                        when (event) {
                            is StepOutputEvent.Stdout -> printAnsi(decoder, event.text, ProcessOutputTypes.STDOUT)
                            is StepOutputEvent.Stderr -> printAnsi(decoder, event.text, ProcessOutputTypes.STDERR)
                            is StepOutputEvent.Failed ->
                                console.print(
                                    "Failed to load output: ${event.error.message}\n",
                                    ConsoleViewContentType.ERROR_OUTPUT,
                                )
                        }
                        printedAny = true
                    }
                if (!printedAny) {
                    console.print("This step has no output.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                }
            }
    }

    private fun printAnsi(
        decoder: AnsiEscapeDecoder,
        text: String,
        outputType: Key<*>,
    ) {
        decoder.escapeText(text, outputType) { chunk, attributes ->
            console.print(chunk, ConsoleViewContentType.getConsoleViewType(attributes))
        }
    }

    private fun selectedStep(): StepKey? {
        val node = stepsTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        val step = node.userObject as? StepNode ?: return null
        return StepKey(step.execution, step.step.num)
    }

    private fun findStep(key: StepKey): Step? {
        return detail?.executions?.firstOrNull { it.index == key.execution }?.steps?.firstOrNull { it.num == key.num }
    }

    private fun findStepPath(key: StepKey): TreePath? {
        return TreeUtil.findNode(stepsRoot) { node ->
            (node.userObject as? StepNode)?.let { StepKey(it.execution, it.step.num) == key } ?: false
        }?.let { TreePath(it.path) }
    }

    private fun loadTestsAndArtifacts() {
        scope.launch {
            service.fetchTests(ref.jobId).fold(
                onSuccess = { testsPanel.setTests(it) },
                onFailure = { testsPanel.showError(it.message ?: "Unknown error") },
            )
        }
        artifactsPanel.load()
    }

    override fun dispose() {
        scope.cancel()
    }

    private data class ExecutionNode(val execution: JobExecution) {
        /** The worst of the steps' statuses: failing beats running beats done. */
        val status: RunStatus
            get() {
                val statuses = execution.steps.map { it.status }
                return statuses.firstOrNull { it.isFailure }
                    ?: statuses.firstOrNull { it.isActive }
                    ?: statuses.lastOrNull()
                    ?: RunStatus.UNKNOWN
            }
    }

    private data class StepNode(val execution: Int, val step: Step)

    private class StepTreeCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            when (val node = (value as? DefaultMutableTreeNode)?.userObject) {
                is ExecutionNode -> {
                    icon = CircleCIIcons.getStatusIcon(node.status)
                    append("Execution ${node.execution.index}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
                is StepNode -> {
                    val step = node.step
                    icon = CircleCIIcons.getStatusIcon(step.status)
                    append(step.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    elapsedSince(step.startedAt, step.endedAt)?.let {
                        append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                    step.exitCode?.takeIf { it != 0 }?.let {
                        append("  exit $it", SimpleTextAttributes.ERROR_ATTRIBUTES)
                    }
                }
                else -> {}
            }
        }
    }

    private companion object {
        /** How often a running job is re-read for new steps and statuses. */
        const val JOB_POLL_INTERVAL_MS = 5_000L
        const val STEPS_PROPORTION = 0.3f
    }
}
