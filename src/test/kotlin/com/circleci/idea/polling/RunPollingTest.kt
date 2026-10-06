package com.circleci.idea.polling

import com.circleci.idea.run.RunStatus
import com.circleci.idea.state.Run
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class RunPollingTest {
    private val now = Instant.parse("2026-10-06T12:00:00Z")

    private fun run(
        status: RunStatus,
        age: Duration = Duration.ofMinutes(1),
    ) = Run(
        id = "$status-$age",
        number = 1,
        projectId = null,
        projectSlug = "gh/org/repo",
        repositoryName = null,
        status = status,
        createdAt = now - age,
        branch = null,
        tag = null,
        revision = null,
        commitSubject = null,
        commitAuthor = null,
        triggeredBy = null,
    )

    @Test
    fun testStartedRunIsInProgress() {
        assertTrue("running", isInProgress(listOf(run(RunStatus.RUNNING, Duration.ofDays(2))), now))
        assertTrue("failing", isInProgress(listOf(run(RunStatus.FAILING)), now))
    }

    @Test
    fun testRunOnHoldIsNotInProgress() {
        assertFalse("on hold", isInProgress(listOf(run(RunStatus.ON_HOLD), run(RunStatus.SUCCESS)), now))
    }

    @Test
    fun testQueuedRunIsInProgressOnlyWhileRecent() {
        assertTrue("queued a minute ago", isInProgress(listOf(run(RunStatus.QUEUED)), now))
        assertFalse("queued a day ago", isInProgress(listOf(run(RunStatus.QUEUED, Duration.ofDays(1))), now))
    }

    @Test
    fun testFinishedRunsAreNotInProgress() {
        assertFalse("none", isInProgress(emptyList(), now))
        assertFalse("ended", isInProgress(listOf(run(RunStatus.SUCCESS), run(RunStatus.FAILED)), now))
    }
}
