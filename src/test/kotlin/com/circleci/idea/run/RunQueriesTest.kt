package com.circleci.idea.run

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class RunQueriesTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")

    @Test
    fun testFilterExpression() {
        assertEquals("none", "", RunQueries.filterExpression(null, null))
        assertEquals("branch", "pipeline.git.branch == \"main\"", RunQueries.filterExpression("main", null))
        assertEquals(
            "status",
            "pipeline.status == \"failed\"",
            RunQueries.filterExpression(null, RunStatusFilter.FAILED),
        )
        assertEquals(
            "both",
            "pipeline.git.branch == \"feat/x\" and pipeline.status == \"not_run\"",
            RunQueries.filterExpression("feat/x", RunStatusFilter.NOT_RUN),
        )
        assertEquals(
            "quotes are escaped",
            "pipeline.git.branch == \"a\\\"b\\\\c\"",
            RunQueries.filterExpression("a\"b\\c", null),
        )
    }

    @Test
    fun testWindowWithoutCreatedFilter() {
        val window = RunQueries.window(null, now)
        assertEquals("from the search horizon", now.minus(Duration.ofDays(90)), window.from)
        assertEquals("to now", now, window.to)
    }

    @Test
    fun testWindowNewerThan() {
        val window = RunQueries.window(CreatedFilter(CreatedAge.HOURS_6, newer = true), now)
        assertEquals("from the cut", now.minus(Duration.ofHours(6)), window.from)
        assertEquals("to now", now, window.to)
    }

    @Test
    fun testWindowOlderThan() {
        val window = RunQueries.window(CreatedFilter(CreatedAge.DAYS_7, newer = false), now)
        assertEquals("from the search horizon", now.minus(Duration.ofDays(90)), window.from)
        assertEquals("to the cut", now.minus(Duration.ofDays(7)), window.to)
    }

    @Test
    fun testStatusDerivation() {
        assertEquals("created", RunStatus.CREATED, RunStatus.fromV3("created", null, null))
        assertEquals("queued", RunStatus.QUEUED, RunStatus.fromV3("queued", null, null))
        assertEquals("running", RunStatus.RUNNING, RunStatus.fromV3("started", null, null))
        assertEquals("failing", RunStatus.FAILING, RunStatus.fromV3("started", null, "failed"))
        assertEquals("canceling", RunStatus.CANCELING, RunStatus.fromV3("started", null, "canceled"))
        assertEquals("outcome wins", RunStatus.FAILED, RunStatus.fromV3("ended", "failed", "succeeded"))
        assertEquals("current outcome fallback", RunStatus.SUCCESS, RunStatus.fromV3("ended", null, "succeeded"))
        assertEquals("empty outcome falls back", RunStatus.CANCELED, RunStatus.fromV3("ended", "", "canceled"))
        assertEquals("timed out is an error", RunStatus.ERROR, RunStatus.fromV3("ended", "timedout", null))
        assertEquals("not run", RunStatus.NOT_RUN, RunStatus.fromV3("ended", null, "not_run"))
        assertEquals("unknown phase", RunStatus.UNKNOWN, RunStatus.fromV3("mystery", null, null))
    }

    @Test
    fun testStatusFilterRoundTripsThroughDerivation() {
        // The my-runs listing filters on phase and current_outcome; each status
        // filter's pair must derive back to the status it stands for.
        for (filter in RunStatusFilter.entries) {
            assertEquals(filter.name, filter.token, RunStatus.fromV3(filter.phase, null, filter.currentOutcome).token)
        }
    }
}
