package com.circleci.idea.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class SshTargetTest {
    @Test
    fun testUserAndCommand() {
        val target = SshTarget(jobId = "abc", execution = 1, jobName = "build", jobNumber = 42)
        assertEquals("user", "abc-1", target.user)
        assertEquals("command", "ssh abc-1@ssh.circleci.com", target.command)
    }

    @Test
    fun testTitle() {
        assertEquals("execution 0 left out", "build #42", SshTarget("abc", 0, "build", 42).title)
        assertEquals("other executions named", "build #42 (execution 2)", SshTarget("abc", 2, "build", 42).title)
        assertEquals("no number", "build", SshTarget("abc", 0, "build", null).title)
    }
}
