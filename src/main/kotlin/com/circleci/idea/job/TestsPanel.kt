package com.circleci.idea.job

import com.circleci.idea.icons.CircleCIIcons
import com.circleci.idea.state.TestOutcome
import com.circleci.idea.state.TestResult
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.util.Locale
import javax.swing.Icon
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

/**
 * A job's tests: filtered by outcome and by name, with the selected test's
 * message below.
 */
class TestsPanel(
    project: Project,
    parent: Disposable,
) : JBPanel<TestsPanel>(BorderLayout()) {
    private var tests: List<TestResult> = emptyList()
    private var filter = TestFilter()

    private val outcomeChoice = ComboBox(OutcomeChoice.ALL)
    private val searchField = SearchTextField(false)
    private val countsLabel = JBLabel()

    private val model = TestsTableModel()
    private val table = JBTable(model)

    private val message: ConsoleView =
        TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console

    init {
        Disposer.register(parent, message)

        outcomeChoice.addActionListener { applyFilter(filter.copy(outcome = outcomeChoice.item?.outcome)) }
        searchField.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    applyFilter(filter.copy(query = searchField.text))
                }
            },
        )

        table.setShowGrid(false)
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.rowSorter = TableRowSorter(model).apply { setComparator(TestsTableModel.TIME, compareBy<Double?> { it }) }
        table.columnModel.getColumn(TestsTableModel.OUTCOME).apply {
            maxWidth = JBUI.scale(OUTCOME_COLUMN_WIDTH)
            cellRenderer = OutcomeRenderer()
        }
        table.emptyText.text = "Loading tests..."
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showMessage() }

        val filters =
            JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                add(outcomeChoice)
                add(searchField)
                add(countsLabel)
            }
        add(filters, BorderLayout.NORTH)
        add(
            OnePixelSplitter(true, TABLE_PROPORTION).apply {
                firstComponent = JBScrollPane(table)
                secondComponent = message.component
            },
            BorderLayout.CENTER,
        )
    }

    /** Show a job's tests, keeping the filter if one was chosen. */
    fun setTests(results: List<TestResult>) {
        val first = tests.isEmpty()
        tests = results
        countsLabel.text = counts(results)
        table.emptyText.text = if (results.isEmpty()) "No tests recorded" else "No tests match the filter"
        if (first) {
            val initial = TestFilter.defaultFor(results)
            outcomeChoice.item = OutcomeChoice.ALL.first { it.outcome == initial.outcome }
            filter = initial.copy(query = searchField.text)
        }
        applyFilter(filter)
    }

    fun showError(error: String) {
        table.emptyText.text = "Failed to load tests: $error"
    }

    private fun applyFilter(newFilter: TestFilter) {
        filter = newFilter
        model.rows = tests.filter { filter.matches(it) }
    }

    private fun showMessage() {
        message.clear()
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        val test = model.rows[table.convertRowIndexToModel(row)]
        if (test.message.isBlank()) {
            message.print("No message.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            return
        }
        // Failure messages keep the test runner's colors.
        AnsiEscapeDecoder().escapeText(test.message, ProcessOutputTypes.STDOUT) { chunk, attributes ->
            message.print(chunk, ConsoleViewContentType.getConsoleViewType(attributes))
        }
    }

    /** An entry in the outcome filter; its text is what the combo box shows. */
    private class OutcomeChoice(val outcome: TestOutcome?) {
        override fun toString(): String = outcome?.label ?: "All"

        companion object {
            val ALL =
                arrayOf(null, TestOutcome.FAILURE, TestOutcome.SKIPPED, TestOutcome.SUCCESS)
                    .map { OutcomeChoice(it) }
                    .toTypedArray()
        }
    }

    private class TestsTableModel : AbstractTableModel() {
        var rows: List<TestResult> = emptyList()
            set(value) {
                field = value
                fireTableDataChanged()
            }

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = COLUMNS.size

        override fun getColumnName(column: Int): String = COLUMNS[column]

        override fun getColumnClass(column: Int): Class<*> =
            if (column == TIME) java.lang.Double::class.java else Any::class.java

        override fun getValueAt(
            row: Int,
            column: Int,
        ): Any? {
            val test = rows[row]
            return when (column) {
                OUTCOME -> test.outcome
                NAME -> test.name
                CLASSNAME -> test.classname
                else -> test.runTime
            }
        }

        companion object {
            const val OUTCOME = 0
            const val NAME = 1
            const val CLASSNAME = 2
            const val TIME = 3
            val COLUMNS = arrayOf("", "Name", "Classname", "Time (s)")
        }
    }

    private class OutcomeRenderer : javax.swing.table.DefaultTableCellRenderer() {
        override fun setValue(value: Any?) {
            val outcome = value as? TestOutcome
            icon = outcome?.let { iconFor(it) }
            text = ""
            toolTipText = outcome?.label
        }

        private fun iconFor(outcome: TestOutcome): Icon? =
            when (outcome) {
                TestOutcome.FAILURE -> CircleCIIcons.Status.FAILED
                TestOutcome.SKIPPED -> CircleCIIcons.Status.CANCELED
                TestOutcome.SUCCESS -> CircleCIIcons.Status.SUCCESS
                TestOutcome.OTHER -> null
            }
    }

    private companion object {
        const val OUTCOME_COLUMN_WIDTH = 28
        const val TABLE_PROPORTION = 0.7f

        fun counts(tests: List<TestResult>): String {
            val byOutcome = tests.groupingBy { it.outcome }.eachCount()
            return listOf(TestOutcome.FAILURE, TestOutcome.SKIPPED, TestOutcome.SUCCESS)
                .mapNotNull {
                        outcome ->
                    byOutcome[outcome]?.let { "${String.format(Locale.ROOT, "%,d", it)} ${outcome.label.lowercase()}" }
                }
                .joinToString(" · ")
        }
    }
}
