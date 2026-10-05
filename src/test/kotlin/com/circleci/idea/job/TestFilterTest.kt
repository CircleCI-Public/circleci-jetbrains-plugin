package com.circleci.idea.job

import com.circleci.idea.api.clients.parseTestResultLines
import com.circleci.idea.state.TestOutcome
import com.circleci.idea.state.TestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestFilterTest {
    private fun test(
        name: String,
        classname: String = "github.com/org/repo/api",
        outcome: TestOutcome = TestOutcome.SUCCESS,
    ) = TestResult(classname = classname, name = name, outcome = outcome, runTime = 0.1, message = "")

    @Test
    fun testOutcomeIsExact() {
        val filter = TestFilter(outcome = TestOutcome.FAILURE)
        assertTrue("failure matches", filter.matches(test("TestA", outcome = TestOutcome.FAILURE)))
        assertFalse("success doesn't", filter.matches(test("TestA", outcome = TestOutcome.SUCCESS)))
        assertTrue("no outcome matches all", TestFilter().matches(test("TestA", outcome = TestOutcome.SKIPPED)))
    }

    @Test
    fun testQueryIsCaseInsensitiveSubstringOfNameOrClassname() {
        val filter = TestFilter(query = "  LOGIN ")
        assertTrue("name", filter.matches(test("TestLoginFlow")))
        assertTrue("classname", filter.matches(test("TestA", classname = "github.com/org/login")))
        assertFalse("neither", filter.matches(test("TestLogout")))
    }

    @Test
    fun testFiltersCombineWithAnd() {
        val filter = TestFilter(outcome = TestOutcome.FAILURE, query = "login")
        assertTrue("both", filter.matches(test("TestLogin", outcome = TestOutcome.FAILURE)))
        assertFalse("only the query", filter.matches(test("TestLogin")))
    }

    @Test
    fun testDefaultShowsFailuresWhenThereAreAny() {
        assertEquals(
            "failures",
            TestOutcome.FAILURE,
            TestFilter.defaultFor(listOf(test("A"), test("B", outcome = TestOutcome.FAILURE))).outcome,
        )
        assertNull("everything when none failed", TestFilter.defaultFor(listOf(test("A"))).outcome)
    }

    @Test
    fun testParseJsonLines() {
        // Lines as GET /api/v3/jobs/{id}/tests returns them.
        val body =
            """
            {"classname":"github.com/org/repo/acceptance","name":"TestOrb/executors","result":"success","run_time":0.02,"message":""}

            {"classname":"github.com/org/repo","name":"TestBroken","result":"failure","run_time":1.5,"message":"boom"}
            """.trimIndent()
        val tests = parseTestResultLines(body.reader())

        assertEquals("two lines, blank skipped", 2, tests.size)
        assertEquals("name", "TestBroken", tests[1].name)
        assertEquals("outcome", TestOutcome.FAILURE, TestOutcome.of(tests[1].result))
        assertEquals("run time", 1.5, tests[1].runTime!!, 0.0)
        assertEquals("unknown result", TestOutcome.OTHER, TestOutcome.of("error"))
    }

    @Test
    fun testSortByColumn() {
        val b = test("b", outcome = TestOutcome.FAILURE).copy(runTime = 2.0)
        val a = test("A").copy(runTime = null)
        val c = test("c", outcome = TestOutcome.SKIPPED).copy(runTime = 1.0)
        val tests = listOf(b, a, c)
        assertEquals("unsorted keeps the job's order", tests, TestSort().apply(tests))
        assertEquals("name, ignoring case", listOf(a, b, c), TestSort(TestColumn.NAME).apply(tests))
        assertEquals("descending", listOf(c, b, a), TestSort(TestColumn.NAME, ascending = false).apply(tests))
        assertEquals("no time first", listOf(a, c, b), TestSort(TestColumn.TIME).apply(tests))
        assertEquals("failures first", listOf(b, c, a), TestSort(TestColumn.OUTCOME).apply(tests))
    }

    @Test
    fun testSortKeepsEachRepeatedTestsPosition() {
        // A test run twice reports twice, the same both times.
        val b = test("b")
        val a = test("a")
        val sorted = TestSort(TestColumn.NAME).apply(listOf(b, a, b).withIndex().toList()) { it.value }

        assertEquals("by name, ties in the job's order", listOf(1, 0, 2), sorted.map { it.index })
    }

    @Test
    fun testSortToggles() {
        val byName = TestSort().toggle(TestColumn.NAME)
        assertEquals("first click ascends", TestSort(TestColumn.NAME, ascending = true), byName)
        assertEquals("second descends", TestSort(TestColumn.NAME, ascending = false), byName.toggle(TestColumn.NAME))
        assertEquals("third unsorts", TestSort(), byName.toggle(TestColumn.NAME).toggle(TestColumn.NAME))
        assertEquals("another column ascends", TestSort(TestColumn.TIME), byName.toggle(TestColumn.TIME))
    }
}
