package com.circleci.idea.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The VS Code extension's parseSSHTarget cases, as "Enable SSH" steps print them. */
class SshEndpointTest {
    @Test
    fun testProxyReadsTheSessionFromTheUsername() {
        val output =
            """
            You can now SSH into this job if your SSH public key is added:
                $ ssh 057f0065-7e6d-4912-921b-50917ff046a3-0@ssh.circleci.com
            """.trimIndent()
        val endpoint = SshEndpoint.parse(output)

        assertEquals(
            "proxy, no port",
            SshEndpoint.Proxy("ssh.circleci.com", null, "057f0065-7e6d-4912-921b-50917ff046a3-0"),
            endpoint,
        )
        assertEquals("command", "ssh 057f0065-7e6d-4912-921b-50917ff046a3-0@ssh.circleci.com", endpoint?.command)
    }

    @Test
    fun testProxyKeepsAPrintedPort() {
        val endpoint = SshEndpoint.parse("    \$ ssh -p 443 057f0065-0@ssh.circleci.com")
        assertEquals("proxy with port", SshEndpoint.Proxy("ssh.circleci.com", 443, "057f0065-0"), endpoint)
        assertEquals("command", "ssh -p 443 057f0065-0@ssh.circleci.com", endpoint?.command)
    }

    @Test
    fun testProxyDoesNotAssumeTheHostname() {
        assertEquals(
            "other host",
            SshEndpoint.Proxy("ssh.example.internal", null, "some-session-id"),
            SshEndpoint.parse("    \$ ssh some-session-id@ssh.example.internal"),
        )
    }

    @Test
    fun testDirectReadsThePortAndAddress() {
        val output =
            """
            You can now SSH into this box if your SSH public key is added:
            $ ssh -p 64535 3.239.179.134
            """.trimIndent()
        val endpoint = SshEndpoint.parse(output)

        assertEquals("direct", SshEndpoint.Direct("3.239.179.134", 64535), endpoint)
        assertEquals("command", "ssh -p 64535 3.239.179.134", endpoint?.command)
    }

    @Test
    fun testDirectIsNotMistakenForAProxyByAnUnrelatedUserAtHost() {
        val output =
            """
            Adding key for user@example.com
            You can now SSH into this box if your SSH public key is added:
            $ ssh -p 64535 3.239.179.134
            """.trimIndent()
        assertEquals("direct", SshEndpoint.Direct("3.239.179.134", 64535), SshEndpoint.parse(output))
    }

    @Test
    fun testDirectTakesTheFirstOfWindowsPerShellCommands() {
        val output =
            """
            You can now SSH into this box if your SSH public key is added:
                $ ssh -t -p 54782 35.226.214.52 -- bash.exe
                $ ssh -t -p 54782 35.226.214.52 -- cmd.exe
                $ ssh -t -p 54782 35.226.214.52 -- powershell.exe
            """.trimIndent()
        assertEquals("windows", SshEndpoint.Direct("35.226.214.52", 54782), SshEndpoint.parse(output))
    }

    @Test
    fun testNeither() {
        assertNull("no instructions yet", SshEndpoint.parse("Setting up SSH...\n"))
        assertNull("empty", SshEndpoint.parse(""))
    }
}
