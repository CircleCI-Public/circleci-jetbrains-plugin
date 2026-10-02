package com.circleci.idea.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class SshTargetTest {
    private val endpoint = SshEndpoint.Proxy("ssh.circleci.com", null, "abc-1")

    @Test
    fun testCommandIsTheEndpoints() {
        assertEquals("command", "ssh abc-1@ssh.circleci.com", SshTarget("abc", 1, "build", 42, endpoint).command)
    }

    @Test
    fun testTitle() {
        assertEquals("execution 0 left out", "build #42", SshTarget("abc", 0, "build", 42, endpoint).title)
        assertEquals(
            "other executions named",
            "build #42 (execution 2)",
            SshTarget("abc", 2, "build", 42, endpoint).title,
        )
        assertEquals("no number", "build", SshTarget("abc", 0, "build", null, endpoint).title)
    }
}
