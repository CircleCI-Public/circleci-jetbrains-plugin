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

class GitHubTokenRecipientsTest {
    @Test
    fun `sends a token`() {
        val recipients = GitHubTokenRecipients<String>()

        assertEquals("sent", "gh-token", recipients.toSend("client", "gh-token"))
    }

    @Test
    fun `sends nothing without a token to a client never sent one`() {
        val recipients = GitHubTokenRecipients<String>()

        assertEquals("sent", null, recipients.toSend("client", null))
    }

    @Test
    fun `clears a token it sent, once`() {
        val recipients = GitHubTokenRecipients<String>()
        recipients.toSend("client", "gh-token")

        val cleared = recipients.toSend("client", null)
        val again = recipients.toSend("client", null)

        assertEquals("cleared", "", cleared)
        assertEquals("again", null, again)
    }

    @Test
    fun `clears only the clients it sent a token`() {
        val recipients = GitHubTokenRecipients<String>()
        recipients.toSend("sent", "gh-token")

        val other = recipients.toSend("other", null)

        assertEquals("other", null, other)
    }
}
