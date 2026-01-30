package com.circleci.idea.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatterTest {
    @Test
    fun testFormatDuration_Null() {
        assertEquals("0s", formatDuration(null))
    }

    @Test
    fun testFormatDuration_Zero() {
        assertEquals("0s", formatDuration(0))
    }

    @Test
    fun testFormatDuration_Seconds() {
        assertEquals("5s", formatDuration(5000))
        assertEquals("30s", formatDuration(30000))
        assertEquals("59s", formatDuration(59000))
    }

    @Test
    fun testFormatDuration_Minutes() {
        assertEquals("1m 0s", formatDuration(60000))
        assertEquals("1m 30s", formatDuration(90000))
        assertEquals("5m 45s", formatDuration(345000))
    }

    @Test
    fun testFormatDuration_Hours() {
        assertEquals("1h 0m 0s", formatDuration(3600000))
        assertEquals("1h 5m 30s", formatDuration(3930000))
        assertEquals("2h 30m 15s", formatDuration(9015000))
    }

    @Test
    fun testFormatDuration_Complex() {
        // 1h 1m 1s
        assertEquals("1h 1m 1s", formatDuration(3661000))
    }

    companion object {
        fun formatDuration(durationMs: Long?): String {
            if (durationMs == null || durationMs == 0L) return "0s"

            val seconds = (durationMs / 1000) % 60
            val minutes = (durationMs / (1000 * 60)) % 60
            val hours = durationMs / (1000 * 60 * 60)

            return when {
                hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
                minutes > 0 -> "${minutes}m ${seconds}s"
                else -> "${seconds}s"
            }
        }
    }
}
