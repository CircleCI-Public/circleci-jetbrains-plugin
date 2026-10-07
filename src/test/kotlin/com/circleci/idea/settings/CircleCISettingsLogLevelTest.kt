package com.circleci.idea.settings

import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.logging.LogLevel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class CircleCISettingsLogLevelTest {
    @After
    fun resetLevel() {
        CircleCILogger.level = LogLevel.INFO
    }

    @Test
    fun `setting the log level sets the logger's`() {
        CircleCISettings().logLevel = "error"

        assertEquals("logger level", LogLevel.ERROR, CircleCILogger.level)
    }

    @Test
    fun `loading the settings sets the logger's level`() {
        val persisted = CircleCISettings().apply { logLevel = "debug" }
        CircleCILogger.level = LogLevel.INFO

        CircleCISettings().loadState(persisted)

        assertEquals("logger level", LogLevel.DEBUG, CircleCILogger.level)
    }
}
