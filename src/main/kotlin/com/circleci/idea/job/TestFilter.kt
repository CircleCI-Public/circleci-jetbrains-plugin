package com.circleci.idea.job

import com.circleci.idea.state.TestOutcome
import com.circleci.idea.state.TestResult

/**
 * Which of a job's tests to show, filtered as `circleci testresult list`
 * does: by outcome, exactly, and by a case-insensitive substring of the name
 * or classname. The tests endpoint takes no filters, so this runs client-side.
 *
 * @property outcome The outcome to show, or null for all
 * @property query Text the name or classname must contain; blank for any
 */
data class TestFilter(
    val outcome: TestOutcome? = null,
    val query: String = "",
) {
    private val text = query.trim()

    fun matches(test: TestResult): Boolean {
        if (outcome != null && test.outcome != outcome) return false
        return text.isEmpty() ||
            test.name.contains(
                text,
                ignoreCase = true,
            ) || test.classname.contains(text, ignoreCase = true)
    }

    companion object {
        /** Failures when there are any, as the CLI shows by default; otherwise everything. */
        fun defaultFor(tests: List<TestResult>): TestFilter =
            TestFilter(outcome = TestOutcome.FAILURE.takeIf { tests.any { it.outcome == TestOutcome.FAILURE } })
    }
}

/** A column the tests can be sorted by. */
enum class TestColumn(val title: String) {
    OUTCOME(""),
    NAME("Name"),
    CLASSNAME("Classname"),
    TIME("Time (s)"),
}

/**
 * How the tests are ordered: by [column], or as the job reported them when
 * it's null. Ties keep the reported order; tests without a time sort first.
 */
data class TestSort(
    val column: TestColumn? = null,
    val ascending: Boolean = true,
) {
    fun apply(tests: List<TestResult>): List<TestResult> = apply(tests) { it }

    /** [items] in the order of their [test]s. */
    fun <T> apply(
        items: List<T>,
        test: (T) -> TestResult,
    ): List<T> {
        val comparator: Comparator<TestResult> =
            when (column) {
                null -> return items
                TestColumn.OUTCOME -> compareBy { it.outcome.ordinal }
                TestColumn.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                TestColumn.CLASSNAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.classname }
                TestColumn.TIME -> compareBy(nullsFirst()) { it.runTime }
            }
        return items.sortedWith(compareBy(if (ascending) comparator else comparator.reversed(), test))
    }

    /** The sort after clicking [clicked]'s heading: ascending, then descending, then unsorted. */
    fun toggle(clicked: TestColumn): TestSort =
        when {
            clicked != column -> TestSort(clicked, ascending = true)
            ascending -> copy(ascending = false)
            else -> TestSort()
        }
}
