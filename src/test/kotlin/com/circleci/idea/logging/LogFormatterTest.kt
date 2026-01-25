package com.circleci.idea.logging

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LogFormatterTest {

    @Test
    fun `formatForFile should include timestamp and level`() {
        val formatted = LogFormatter.formatForFile(LogLevel.INFO, "Test message")

        assertTrue(formatted.contains("[INFO ]"))
        assertTrue(formatted.contains("Test message"))
        assertTrue(formatted.matches(Regex(".*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}.*")))
    }

    @Test
    fun `formatForFile should include context if provided`() {
        val context = LogContext("1.0.0", "IntelliJ IDEA 2023.2", "macOS 13.0")
        val formatted = LogFormatter.formatForFile(LogLevel.INFO, "Test message", context)

        assertTrue(formatted.contains("Test message"))
        assertTrue(formatted.contains("[1.0.0]"))
    }

    @Test
    fun `formatForFile should include exception stack trace`() {
        val exception = RuntimeException("Test exception")
        val formatted = LogFormatter.formatForFile(LogLevel.ERROR, "Error occurred", throwable = exception)

        assertTrue(formatted.contains("Error occurred"))
        assertTrue(formatted.contains("RuntimeException"))
        assertTrue(formatted.contains("Test exception"))
    }

    @Test
    fun `formatForConsole should include CircleCI prefix`() {
        val formatted = LogFormatter.formatForConsole(LogLevel.INFO, "Test message")

        assertTrue(formatted.startsWith("[CircleCI]"))
        assertTrue(formatted.contains("Test message"))
    }

    @Test
    fun `formatForConsole should include exception message`() {
        val exception = RuntimeException("Something went wrong")
        val formatted = LogFormatter.formatForConsole(LogLevel.ERROR, "Error", exception)

        assertTrue(formatted.contains("Error"))
        assertTrue(formatted.contains("Something went wrong"))
    }

    @Test
    fun `formatApiRequest should format request without status code`() {
        val formatted = LogFormatter.formatApiRequest("GET", "/api/v2/pipelines")

        assertEquals("API GET /api/v2/pipelines", formatted)
    }

    @Test
    fun `formatApiRequest should format request with status code`() {
        val formatted = LogFormatter.formatApiRequest("POST", "/api/v2/workflow/rerun", 200)

        assertEquals("API POST /api/v2/workflow/rerun -> 200", formatted)
    }

    @Test
    fun `formatWebSocketEvent should format event without channel`() {
        val formatted = LogFormatter.formatWebSocketEvent("connected")

        assertEquals("WebSocket connected", formatted)
    }

    @Test
    fun `formatWebSocketEvent should format event with channel`() {
        val formatted = LogFormatter.formatWebSocketEvent("workflow.completed", "private-project-123")

        assertEquals("WebSocket [private-project-123] workflow.completed", formatted)
    }

    @Test
    fun `formatStateChange should format state transition`() {
        val formatted = LogFormatter.formatStateChange("pipelines", "loading", "loaded")

        assertEquals("State[pipelines] loading -> loaded", formatted)
    }

    @Test
    fun `formatStateChange should handle null values`() {
        val formatted = LogFormatter.formatStateChange("user", null, "authenticated")

        assertTrue(formatted.contains("State[user]"))
        assertTrue(formatted.contains("null"))
        assertTrue(formatted.contains("authenticated"))
    }
}
