package com.circleci.idea.job

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circleci.idea.state.TestOutcome
import com.circleci.idea.state.TestResult
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.jewel.foundation.lazy.SingleSelectionLazyColumn
import org.jetbrains.jewel.foundation.lazy.items
import org.jetbrains.jewel.foundation.lazy.rememberSingleSelectionLazyListState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.SplitLayoutState
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalSplitLayout
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.util.Locale

/**
 * A job's tests: filtered by outcome and by name, sortable by column, with
 * the selected test's message below.
 *
 * What's shown, filtered, sorted and selected lives here rather than in the
 * view, so it outlasts the view leaving the screen.
 */
class TestsTab(
    project: Project,
    parent: Disposable,
) {
    private val _content = MutableStateFlow<TestsContent>(TestsContent.Waiting("Loading tests..."))
    val content: StateFlow<TestsContent> = _content.asStateFlow()

    private val _filter = MutableStateFlow(TestFilter())
    val filter: StateFlow<TestFilter> = _filter.asStateFlow()

    private val _sort = MutableStateFlow(TestSort())
    val sort: StateFlow<TestSort> = _sort.asStateFlow()

    /** The selected test, by its position in the job's tests. */
    private val _selected = MutableStateFlow<Int?>(null)
    val selected: StateFlow<Int?> = _selected.asStateFlow()

    internal val query = TextFieldState()
    internal val split = SplitLayoutState(TABLE_PROPORTION)

    private val message: ConsoleView =
        TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console

    init {
        Disposer.register(parent, message)
    }

    /** Show a job's tests, keeping the filter if one was chosen. */
    fun setTests(results: List<TestResult>) {
        val first = (_content.value as? TestsContent.Loaded)?.tests.isNullOrEmpty()
        if (first) _filter.value = TestFilter.defaultFor(results).copy(query = _filter.value.query)
        _content.value = TestsContent.Loaded(results)
        select(null)
    }

    fun showError(error: String) {
        _content.value = TestsContent.Waiting("Failed to load tests: $error")
    }

    fun setOutcome(outcome: TestOutcome?) {
        _filter.value = _filter.value.copy(outcome = outcome)
    }

    fun setQuery(text: String) {
        _filter.value = _filter.value.copy(query = text)
    }

    fun sortBy(column: TestColumn) {
        _sort.value = _sort.value.toggle(column)
    }

    fun select(index: Int?) {
        if (index == _selected.value && index != null) return
        _selected.value = index
        showMessage(index?.let { (_content.value as? TestsContent.Loaded)?.tests?.getOrNull(it) })
    }

    private fun showMessage(test: TestResult?) {
        message.clear()
        if (test == null) return
        if (test.message.isBlank()) {
            message.print("No message.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            return
        }
        // Failure messages keep the test runner's colors.
        AnsiEscapeDecoder().escapeText(test.message, ProcessOutputTypes.STDOUT) { chunk, attributes ->
            message.print(chunk, ConsoleViewContentType.getConsoleViewType(attributes))
        }
    }

    @Composable
    fun View() {
        val content by content.collectAsState()
        val filter by filter.collectAsState()
        Column(Modifier.fillMaxSize()) {
            FilterBar(filter, (content as? TestsContent.Loaded)?.tests.orEmpty())
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            when (val current = content) {
                is TestsContent.Waiting -> Placeholder(current.text)
                is TestsContent.Loaded ->
                    VerticalSplitLayout(
                        first = { TestTable(current.tests, filter) },
                        second = { SwingComponent(message.component, Modifier.fillMaxSize()) },
                        modifier = Modifier.fillMaxSize(),
                        firstPaneMinWidth = MIN_PANE.dp,
                        secondPaneMinWidth = MIN_PANE.dp,
                        state = split,
                    )
            }
        }
    }

    @Composable
    private fun FilterBar(
        filter: TestFilter,
        tests: List<TestResult>,
    ) {
        LaunchedEffect(query) { snapshotFlow { query.text.toString() }.collect { setQuery(it) } }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ListComboBox(
                items = OUTCOMES.map { it?.label ?: "All" },
                selectedIndex = OUTCOMES.indexOf(filter.outcome),
                onSelectedItemChange = { setOutcome(OUTCOMES[it]) },
                modifier = Modifier.width(OUTCOME_CHOICE_WIDTH.dp),
            )
            TextField(
                query,
                modifier = Modifier.width(SEARCH_WIDTH.dp),
                placeholder = { Text("Name or classname") },
                leadingIcon = {
                    Icon(AllIconsKeys.Actions.Search, null, Modifier.padding(end = 4.dp))
                },
            )
            Counts(tests)
        }
    }

    @Composable
    private fun TestTable(
        tests: List<TestResult>,
        filter: TestFilter,
    ) {
        val sort by sort.collectAsState()
        val selected by selected.collectAsState()
        val rows =
            remember(tests, filter, sort) {
                val indexed = tests.withIndex().filter { filter.matches(it.value) }
                val order = sort.apply(indexed.map { it.value })
                // Sorting is stable, so equal tests keep their positions' order.
                val byTest = indexed.groupBy({ it.value }, { it.index }).mapValues { it.value.toMutableList() }
                order.map { IndexedValue(byTest.getValue(it).removeAt(0), it) }
            }
        Column(Modifier.fillMaxSize()) {
            HeaderRow(sort)
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            if (rows.isEmpty()) {
                Placeholder(if (tests.isEmpty()) "No tests recorded" else "No tests match the filter")
                return@Column
            }
            val listState = rememberSingleSelectionLazyListState(initialSelectedKey = selected)
            LaunchedEffect(selected) { listState.selectedKeys = setOfNotNull(selected) }
            VerticallyScrollableContainer(listState, Modifier.fillMaxSize()) {
                SingleSelectionLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    onSelectedIndexesChange = {
                            indexes ->
                        select(indexes.firstOrNull()?.let { rows.getOrNull(it)?.index })
                    },
                ) {
                    items(rows, key = { it.index }) { row ->
                        TestRow(row.value, Modifier.rowBackground(isSelected, isActive))
                    }
                }
            }
        }
    }

    @Composable
    private fun HeaderRow(sort: TestSort) {
        TableRow(Modifier.padding(vertical = 2.dp)) {
            for (column in TestColumn.entries) {
                val arrow = if (sort.column == column) (if (sort.ascending) " ↑" else " ↓") else ""
                Cell(column, Modifier.clickable { sortBy(column) }) {
                    Text(
                        column.title + arrow,
                        color = JewelTheme.globalColors.text.info,
                        textAlign = if (column == TestColumn.TIME) TextAlign.End else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                    )
                }
            }
        }
    }

    @Composable
    private fun TestRow(
        test: TestResult,
        modifier: Modifier,
    ) {
        TableRow(modifier) {
            Cell(TestColumn.OUTCOME) {
                outcomeIconKey(test.outcome)?.let { Icon(it, test.outcome.label, Modifier.size(ICON.dp)) }
                    ?: Spacer(Modifier.size(ICON.dp))
            }
            Cell(TestColumn.NAME) { Ellipsized(test.name) }
            Cell(TestColumn.CLASSNAME) { Ellipsized(test.classname, JewelTheme.globalColors.text.info) }
            Cell(TestColumn.TIME) {
                Text(
                    test.runTime?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "",
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    private companion object {
        const val TABLE_PROPORTION = 0.7f
        const val MIN_PANE = 60
        const val OUTCOME_CHOICE_WIDTH = 110
        const val SEARCH_WIDTH = 240

        val OUTCOMES = listOf(null, TestOutcome.FAILURE, TestOutcome.SKIPPED, TestOutcome.SUCCESS)
    }
}

/** What the tests tab has to show. */
sealed interface TestsContent {
    /** Nothing yet, or a failure to load: why, in words. */
    data class Waiting(val text: String) : TestsContent

    data class Loaded(val tests: List<TestResult>) : TestsContent
}

@Composable
private fun Counts(tests: List<TestResult>) {
    val byOutcome = remember(tests) { tests.groupingBy { it.outcome }.eachCount() }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        for (outcome in COUNTED) {
            val count = byOutcome[outcome] ?: continue
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                outcomeIconKey(outcome)?.let { Icon(it, outcome.label, Modifier.size(ICON.dp)) }
                Text(
                    "${String.format(Locale.ROOT, "%,d", count)} ${outcome.label.lowercase()}",
                    color = JewelTheme.globalColors.text.info,
                )
            }
        }
    }
}

@Composable
private fun TableRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().height(ROW_HEIGHT.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun RowScope.Cell(
    column: TestColumn,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val width =
        when (column) {
            TestColumn.OUTCOME -> Modifier.width(ICON.dp)
            TestColumn.NAME -> Modifier.weight(NAME_WEIGHT)
            TestColumn.CLASSNAME -> Modifier.weight(CLASSNAME_WEIGHT)
            TestColumn.TIME -> Modifier.width(TIME_WIDTH.dp)
        }
    Box(width.then(modifier), contentAlignment = Alignment.CenterStart) { content() }
}

/** One line, cut short with an ellipsis, and whole in a tooltip. */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip is built on TooltipArea
@Composable
private fun Ellipsized(
    text: String,
    color: Color = Color.Unspecified,
) {
    Tooltip(tooltip = { Text(text) }) {
        Text(text, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val ROW_HEIGHT = 24
private const val ICON = 16
private const val TIME_WIDTH = 72
private const val NAME_WEIGHT = 0.55f
private const val CLASSNAME_WEIGHT = 0.45f
private val COUNTED = listOf(TestOutcome.FAILURE, TestOutcome.SKIPPED, TestOutcome.SUCCESS)
