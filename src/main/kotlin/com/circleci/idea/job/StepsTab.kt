package com.circleci.idea.job

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circleci.idea.icons.StatusDot
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
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.lazy.tree.rememberTreeState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.InlineErrorBanner
import org.jetbrains.jewel.ui.component.InlineSuccessBanner
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.SplitLayoutState
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography
import java.awt.datatransfer.StringSelection

/** A step, by the execution it ran in and its number. */
data class StepKey(val execution: Int, val num: Int)

/** What a row in the steps tree stands for. */
sealed interface StepsItem {
    data class Execution(val execution: JobExecution) : StepsItem {
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

    data class StepItem(val key: StepKey, val step: Step) : StepsItem
}

/**
 * A job's steps (by parallel execution, when there's more than one) beside
 * the selected step's output, streamed while it runs.
 */
@Suppress("LongParameterList")
class StepsTab(
    project: Project,
    private val ref: JobRef,
    private val scope: CoroutineScope,
    private val service: JobDetailsService,
    parent: Disposable,
    // Whether the steps are on screen, for a running step's output to wait while they're not.
    private val showing: StateFlow<Boolean>,
    private val detail: () -> JobDetail?,
) {
    /** The job's executions as last read, or null until the first read. */
    private val _executions = MutableStateFlow<List<JobExecution>?>(null)
    val executions: StateFlow<List<JobExecution>?> = _executions.asStateFlow()

    private val _selected = MutableStateFlow<StepKey?>(null)
    val selected: StateFlow<StepKey?> = _selected.asStateFlow()

    internal val split = SplitLayoutState(STEPS_PROPORTION)

    // Executions start open; these are the ones the user closed.
    private var closed: Set<Any> = emptySet()

    private val console: ConsoleView =
        TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console

    private var streamJob: Job? = null
    private var streamingStep: StepKey? = null

    // Pick a step to show once, when the job first loads; after that the
    // selection is the user's.
    private var autoSelected = false

    init {
        Disposer.register(parent, console)
        console.print("Select a step to see its output.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    /** Show the job's steps as last read. */
    fun show(executions: List<JobExecution>) {
        _executions.value = executions
        if (_selected.value == null && !autoSelected) {
            stepToShow(executions)?.let {
                autoSelected = true
                select(it)
            }
        }
    }

    fun select(key: StepKey) {
        _selected.value = key
        streamStep(key)
    }

    @OptIn(ExperimentalJewelApi::class)
    @Composable
    fun View() {
        HorizontalSplitLayout(
            first = { StepTree() },
            second = { StepOutput() },
            modifier = Modifier.fillMaxSize(),
            firstPaneMinWidth = MIN_PANE.dp,
            secondPaneMinWidth = MIN_PANE.dp,
            state = split,
        )
    }

    @OptIn(ExperimentalJewelApi::class)
    @Composable
    private fun StepTree() {
        val executions by executions.collectAsState()
        val selected by selected.collectAsState()
        val current = executions
        when {
            current == null -> return Placeholder("Loading steps...")
            current.all { it.steps.isEmpty() } -> return Placeholder("No steps yet")
        }
        val all = current.orEmpty()
        val tree = remember(all) { stepsTree(all) }
        val executionIds = remember(all) { if (all.size > 1) all.map { executionId(it.index) }.toSet() else emptySet() }
        // Shared with the scrollbar, which Jewel's tree doesn't draw itself.
        val scroll = rememberLazyListState()
        val treeState = rememberTreeState(scroll)
        LaunchedEffect(tree) { treeState.openNodes = executionIds - closed }
        LaunchedEffect(treeState, executionIds) {
            snapshotFlow { treeState.openNodes }.collect { closed = executionIds - it }
        }
        LaunchedEffect(tree, selected) { treeState.selectedKeys = setOfNotNull(selected) }
        VerticallyScrollableContainer(scroll, Modifier.fillMaxSize()) {
            LazyTree(
                tree = tree,
                modifier = Modifier.fillMaxSize().focusable(),
                treeState = treeState,
                onSelectionChange = { elements ->
                    (elements.firstOrNull()?.data as? StepsItem.StepItem)?.let {
                        if (it.key != _selected.value) {
                            select(
                                it.key,
                            )
                        }
                    }
                },
            ) { element ->
                when (val item = element.data) {
                    is StepsItem.Execution -> ExecutionRow(item)
                    is StepsItem.StepItem -> StepRow(item.step)
                }
            }
        }
    }

    /** The step's output, with its command above it where it has one, and its exit code below once it has one. */
    @Composable
    private fun StepOutput() {
        val executions by executions.collectAsState()
        val selected by selected.collectAsState()
        val step =
            selected?.let { key ->
                executions?.firstOrNull { it.index == key.execution }?.steps?.firstOrNull { it.num == key.num }
            }
        Column(Modifier.fillMaxSize()) {
            step?.command?.let { CommandBar(it) }
            SwingComponent(console.component, Modifier.fillMaxWidth().weight(1f))
            step?.exitCode?.let { ExitCodeBanner(it) }
        }
    }

    private fun streamStep(key: StepKey) {
        // Re-reading the job reselects the step being shown.
        if (key == streamingStep) return
        streamingStep = key
        streamJob?.cancel()
        console.clear()

        val decoder = AnsiEscapeDecoder()
        streamJob =
            scope.launch {
                var printedAny = false
                val awaitShowing: suspend () -> Unit = { showing.first { it } }
                service.stepOutput(ref.jobId, key.execution, key.num, { findStep(key) }, awaitShowing).events()
                    .map { decode(decoder, it) }
                    .flowOn(Dispatchers.IO)
                    .collect { pieces ->
                        pieces.forEach { console.print(it.text.toString(), it.type) }
                        printedAny = true
                    }
                if (!printedAny) {
                    console.print("This step has no output.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                }
            }
    }

    private fun findStep(key: StepKey): Step? =
        detail()?.executions?.firstOrNull { it.index == key.execution }?.steps?.firstOrNull { it.num == key.num }

    /** Output to print, in a console content type. */
    private class Piece(val text: StringBuilder, val type: ConsoleViewContentType)

    private companion object {
        const val STEPS_PROPORTION = 0.3f
        const val MIN_PANE = 120

        /** The step worth opening on: the first that failed, or else the one running now. */
        fun stepToShow(executions: List<JobExecution>): StepKey? {
            val steps =
                executions.flatMap {
                        execution ->
                    execution.steps.map { StepKey(execution.index, it.num) to it }
                }
            val step =
                steps.firstOrNull { (_, step) -> step.status.isFailure }
                    ?: steps.lastOrNull { (_, step) -> step.status.isActive }
            return step?.first
        }

        fun executionId(index: Int): String = "execution:$index"

        /** [event] as the console prints it, its ANSI escapes decoded, in one piece for each run of a content type. */
        fun decode(
            decoder: AnsiEscapeDecoder,
            event: StepOutputEvent,
        ): List<Piece> =
            when (event) {
                is StepOutputEvent.Stdout -> decodeAnsi(decoder, event.text, ProcessOutputTypes.STDOUT)
                is StepOutputEvent.Stderr -> decodeAnsi(decoder, event.text, ProcessOutputTypes.STDERR)
                is StepOutputEvent.Skipped ->
                    listOf(
                        Piece(
                            StringBuilder("… ${StringUtil.formatFileSize(event.bytes)} of earlier output not shown\n"),
                            ConsoleViewContentType.SYSTEM_OUTPUT,
                        ),
                    )
                is StepOutputEvent.Failed ->
                    listOf(
                        Piece(
                            StringBuilder("Failed to load output: ${event.error.message}\n"),
                            ConsoleViewContentType.ERROR_OUTPUT,
                        ),
                    )
            }

        fun decodeAnsi(
            decoder: AnsiEscapeDecoder,
            text: String,
            outputType: Key<*>,
        ): List<Piece> {
            val pieces = mutableListOf<Piece>()
            decoder.escapeText(text, outputType) { chunk, attributes ->
                val type = ConsoleViewContentType.getConsoleViewType(attributes)
                val last = pieces.lastOrNull()
                if (last?.type == type) last.text.append(chunk) else pieces += Piece(StringBuilder(chunk), type)
            }
            return pieces
        }

        /**
         * Only executions open and close; a lone execution's steps are the
         * whole tree, with no node above them to indent them under.
         */
        fun stepsTree(executions: List<JobExecution>) =
            buildTree<StepsItem> {
                if (executions.size == 1) {
                    executions[0].steps.forEach { step ->
                        val key = StepKey(executions[0].index, step.num)
                        addLeaf(StepsItem.StepItem(key, step), key)
                    }
                } else {
                    for (execution in executions) {
                        addNode(StepsItem.Execution(execution), executionId(execution.index)) {
                            execution.steps.forEach { step ->
                                val key = StepKey(execution.index, step.num)
                                addLeaf(StepsItem.StepItem(key, step), key)
                            }
                        }
                    }
                }
            }
    }
}

@Composable
private fun ExecutionRow(item: StepsItem.Execution) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusDot(item.status)
        Text("Execution ${item.execution.index}")
    }
}

@Composable
private fun StepRow(step: Step) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusDot(step.status)
        Text(step.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        elapsedSince(step.startedAt, step.endedAt)?.let {
            Text(it, color = JewelTheme.globalColors.text.info, style = JewelTheme.typography.small)
        }
        step.exitCode?.takeIf { it != 0 }?.let {
            Text("exit $it", color = JewelTheme.globalColors.text.error, style = JewelTheme.typography.small)
        }
    }
}

/** A run step's script, as the web app shows it above the output: selectable, and scrolling if it's long. */
@Composable
private fun CommandBar(command: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(JewelTheme.globalColors.panelBackground)
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        VerticallyScrollableContainer(
            modifier = Modifier.weight(1f).heightIn(max = COMMAND_MAX_HEIGHT.dp),
            scrollState = rememberScrollState(),
        ) {
            SelectionContainer { Text(command, style = JewelTheme.editorTextStyle) }
        }
        CopyButton(command)
    }
    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
}

@OptIn(ExperimentalFoundationApi::class) // Jewel's tooltips are built on TooltipArea
@Composable
private fun CopyButton(command: String) {
    IconActionButton(
        AllIconsKeys.Actions.Copy,
        "Copy Command",
        { CopyPasteManager.getInstance().setContents(StringSelection(command)) },
    ) { Text("Copy Command") }
}

/** "CircleCI received exit code 1", as the web app shows it below the output. */
@Composable
private fun ExitCodeBanner(exitCode: Int) {
    val text = "CircleCI received exit code $exitCode"
    val modifier = Modifier.fillMaxWidth().padding(6.dp)
    if (exitCode == 0) InlineSuccessBanner(text, modifier) else InlineErrorBanner(text, modifier)
}

private const val COMMAND_MAX_HEIGHT = 120
