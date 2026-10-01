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
        val tests = parseTestResultLines(body)

        assertEquals("two lines, blank skipped", 2, tests.size)
        assertEquals("name", "TestBroken", tests[1].name)
        assertEquals("outcome", TestOutcome.FAILURE, TestOutcome.of(tests[1].result))
        assertEquals("run time", 1.5, tests[1].runTime!!, 0.0)
        assertEquals("unknown result", TestOutcome.OTHER, TestOutcome.of("error"))
    }
}
