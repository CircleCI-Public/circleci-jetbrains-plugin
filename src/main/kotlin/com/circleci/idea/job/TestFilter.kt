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
    fun matches(test: TestResult): Boolean {
        if (outcome != null && test.outcome != outcome) return false
        val text = query.trim()
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
