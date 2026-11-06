package com.circleci.idea.lsp

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageServerAuthTest {
    @Test
    fun `sends the token, then the host`() {
        val commands = authCommands(LanguageServerCredentials("token", "https://circleci.example.com"))

        assertEquals(
            "commands",
            listOf("setToken" to listOf("token"), "setSelfHostedUrl" to listOf("https://circleci.example.com")),
            commands.map { it.command to it.arguments },
        )
    }

    @Test
    fun `sends an empty token when logged out`() {
        val commands = authCommands(LanguageServerCredentials("", "https://circleci.com"))

        assertEquals("token", listOf(""), commands.first().arguments)
    }
}
