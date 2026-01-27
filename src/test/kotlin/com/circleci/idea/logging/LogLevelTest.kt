package com.circleci.idea.logging

import org.junit.Assert.*
import org.junit.Test

class LogLevelTest {
    @Test
    fun `fromString should parse valid log levels`() {
        assertEquals(LogLevel.DEBUG, LogLevel.fromString("debug"))
        assertEquals(LogLevel.INFO, LogLevel.fromString("info"))
        assertEquals(LogLevel.WARN, LogLevel.fromString("warn"))
        assertEquals(LogLevel.ERROR, LogLevel.fromString("error"))
    }

    @Test
    fun `fromString should be case insensitive`() {
        assertEquals(LogLevel.DEBUG, LogLevel.fromString("DEBUG"))
        assertEquals(LogLevel.INFO, LogLevel.fromString("Info"))
        assertEquals(LogLevel.WARN, LogLevel.fromString("WARN"))
        assertEquals(LogLevel.ERROR, LogLevel.fromString("ErRoR"))
    }

    @Test
    fun `fromString should default to INFO for invalid levels`() {
        assertEquals(LogLevel.INFO, LogLevel.fromString("invalid"))
        assertEquals(LogLevel.INFO, LogLevel.fromString(""))
        assertEquals(LogLevel.INFO, LogLevel.fromString("trace"))
    }

    @Test
    fun `shouldLog should respect log level hierarchy`() {
        // DEBUG level should log everything
        assertTrue(LogLevel.DEBUG.shouldLog(LogLevel.DEBUG))
        assertTrue(LogLevel.INFO.shouldLog(LogLevel.DEBUG))
        assertTrue(LogLevel.WARN.shouldLog(LogLevel.DEBUG))
        assertTrue(LogLevel.ERROR.shouldLog(LogLevel.DEBUG))

        // INFO level should not log DEBUG
        assertFalse(LogLevel.DEBUG.shouldLog(LogLevel.INFO))
        assertTrue(LogLevel.INFO.shouldLog(LogLevel.INFO))
        assertTrue(LogLevel.WARN.shouldLog(LogLevel.INFO))
        assertTrue(LogLevel.ERROR.shouldLog(LogLevel.INFO))

        // WARN level should only log WARN and ERROR
        assertFalse(LogLevel.DEBUG.shouldLog(LogLevel.WARN))
        assertFalse(LogLevel.INFO.shouldLog(LogLevel.WARN))
        assertTrue(LogLevel.WARN.shouldLog(LogLevel.WARN))
        assertTrue(LogLevel.ERROR.shouldLog(LogLevel.WARN))

        // ERROR level should only log ERROR
        assertFalse(LogLevel.DEBUG.shouldLog(LogLevel.ERROR))
        assertFalse(LogLevel.INFO.shouldLog(LogLevel.ERROR))
        assertFalse(LogLevel.WARN.shouldLog(LogLevel.ERROR))
        assertTrue(LogLevel.ERROR.shouldLog(LogLevel.ERROR))
    }

    @Test
    fun `log level values should be in correct order`() {
        assertTrue(LogLevel.DEBUG.value < LogLevel.INFO.value)
        assertTrue(LogLevel.INFO.value < LogLevel.WARN.value)
        assertTrue(LogLevel.WARN.value < LogLevel.ERROR.value)
    }
}
