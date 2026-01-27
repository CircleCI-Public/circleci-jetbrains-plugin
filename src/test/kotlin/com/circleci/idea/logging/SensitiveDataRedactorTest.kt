package com.circleci.idea.logging

import org.junit.Assert.*
import org.junit.Test

class SensitiveDataRedactorTest {

    @Test
    fun `should redact Circle-Token header`() {
        val message = "Circle-Token: abc123def456ghi789jkl012"
        val redacted = SensitiveDataRedactor.redact(message)
        assertFalse(redacted.contains("abc123def456ghi789jkl012"))
        assertTrue(redacted.contains("Circle-Token:"))
        assertTrue(redacted.contains("***"))
    }

    @Test
    fun `should redact circle_token field`() {
        val message = "circle_token: secrettoken123456"
        val redacted = SensitiveDataRedactor.redact(message)
        assertFalse(redacted.contains("secrettoken123456"))
        assertTrue(redacted.contains("circle_token:"))
    }

    @Test
    fun `should redact API key`() {
        val message = "api_key: myapikey12345"
        val redacted = SensitiveDataRedactor.redact(message)
        assertFalse(redacted.contains("myapikey12345"))
        assertTrue(redacted.contains("api_key:"))
    }

    @Test
    fun `should redact password`() {
        val message = "password: mySecretPassword123"
        val redacted = SensitiveDataRedactor.redact(message)
        assertFalse(redacted.contains("mySecretPassword123"))
        assertTrue(redacted.contains("password: **********"))
    }

    @Test
    fun `should redact authorization header`() {
        val message = "Authorization: Bearer mytoken123"
        val redacted = SensitiveDataRedactor.redact(message)
        assertFalse(redacted.contains("mytoken123"))
        assertTrue(redacted.contains("Authorization:"))
    }

    @Test
    fun `should handle case insensitive patterns`() {
        val message1 = "CIRCLE-TOKEN: token123"
        val message2 = "circle-token: token123"
        val message3 = "Circle-Token: token123"

        assertFalse(SensitiveDataRedactor.redact(message1).contains("token123"))
        assertFalse(SensitiveDataRedactor.redact(message2).contains("token123"))
        assertFalse(SensitiveDataRedactor.redact(message3).contains("token123"))
    }

    @Test
    fun `redactToken should replace specific token`() {
        val message = "Request with token abc123 was successful"
        val redacted = SensitiveDataRedactor.redactToken(message, "abc123")
        assertFalse(redacted.contains("abc123"))
        assertTrue(redacted.contains("***"))
    }

    @Test
    fun `redactToken should handle null token`() {
        val message = "No token here"
        val redacted = SensitiveDataRedactor.redactToken(message, null)
        assertEquals(message, redacted)
    }

    @Test
    fun `redactMap should redact token keys`() {
        val map = mapOf(
            "Content-Type" to "application/json",
            "Circle-Token" to "secrettoken123",
            "Authorization" to "Bearer mytoken456"
        )

        val redacted = SensitiveDataRedactor.redactMap(map)

        assertEquals("application/json", redacted["Content-Type"])
        assertFalse(redacted["Circle-Token"]!!.contains("secrettoken123"))
        assertFalse(redacted["Authorization"]!!.contains("mytoken456"))
    }

    @Test
    fun `should not redact normal text`() {
        val message = "Pipeline #123 completed successfully on branch main"
        val redacted = SensitiveDataRedactor.redact(message)
        assertEquals(message, redacted)
    }

    @Test
    fun `should preserve readability after redaction`() {
        val message = "API request failed with Circle-Token: abc123def456"
        val redacted = SensitiveDataRedactor.redact(message)
        assertTrue(redacted.contains("API request failed"))
        assertTrue(redacted.contains("Circle-Token:"))
    }
}
