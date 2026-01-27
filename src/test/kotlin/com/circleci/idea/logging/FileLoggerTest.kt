package com.circleci.idea.logging

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class FileLoggerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val tempDir: Path
        get() = tempFolder.root.toPath()

    @Test
    fun `should create log file`() {
        val logger = FileLogger(tempDir, maxFileSizeBytes = 1024, maxFiles = 3)

        logger.write("Test log message")
        logger.close()

        val logFile = tempDir.resolve("circleci.log")
        assertTrue(Files.exists(logFile))

        val content = Files.readString(logFile)
        assertTrue(content.contains("Test log message"))
    }

    @Test
    fun `should append to existing log file`() {
        val logger = FileLogger(tempDir)

        logger.write("First message")
        logger.write("Second message")
        logger.close()

        val logFile = tempDir.resolve("circleci.log")
        val content = Files.readString(logFile)

        assertTrue(content.contains("First message"))
        assertTrue(content.contains("Second message"))
    }

    @Test
    fun `should rotate log files when size limit exceeded`() {
        val maxSize = 100L // Very small to trigger rotation
        val logger = FileLogger(tempDir, maxFileSizeBytes = maxSize, maxFiles = 3)

        // Write enough data to trigger rotation
        repeat(20) {
            logger.write("This is log message number $it with enough text to exceed size limit")
        }
        logger.close()

        // Check that rotated files were created
        val logFile1 = tempDir.resolve("circleci.log.1")
        assertTrue("Rotated log file should exist", Files.exists(logFile1))
    }

    @Test
    fun `should keep only maxFiles log files`() {
        val maxSize = 50L
        val maxFiles = 3
        val logger = FileLogger(tempDir, maxFileSizeBytes = maxSize, maxFiles = maxFiles)

        // Write enough to create more than maxFiles rotations
        repeat(200) {
            logger.write("Log message $it with sufficient content to trigger multiple rotations")
        }
        logger.close()

        // Count log files
        val logFileCount =
            Files.list(tempDir)
                .filter { it.fileName.toString().startsWith("circleci.log") }
                .count()

        assertTrue("Should not exceed maximum number of log files", logFileCount <= maxFiles)
    }

    @Test
    fun `getCurrentLogFilePath should return correct path`() {
        val logger = FileLogger(tempDir)

        val path = logger.getCurrentLogFilePath()
        assertTrue(path.endsWith("circleci.log"))
        assertTrue(path.contains(tempDir.toString()))

        logger.close()
    }

    @Test
    fun `clearLogs should delete all log files`() {
        val logger = FileLogger(tempDir)

        logger.write("Message 1")
        logger.write("Message 2")
        logger.write("Message 3")

        // Verify log file exists
        val logFile = tempDir.resolve("circleci.log")
        assertTrue(Files.exists(logFile))

        // Clear logs
        logger.clearLogs()

        // Verify log files are deleted
        val filesAfterClear = Files.list(tempDir).count()
        assertEquals("All log files should be deleted", 0L, filesAfterClear)

        logger.close()
    }

    @Test
    fun `should handle concurrent writes safely`() {
        val logger = FileLogger(tempDir)
        val threads =
            (1..10).map { threadNum ->
                Thread {
                    repeat(10) {
                        logger.write("Thread $threadNum message $it")
                    }
                }
            }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        logger.close()

        val logFile = tempDir.resolve("circleci.log")
        val content = Files.readString(logFile)
        val lineCount = content.lines().filter { it.isNotBlank() }.size

        assertEquals("All messages should be written", 100, lineCount)
    }
}
