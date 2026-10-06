package com.circleci.idea.logging

import com.intellij.util.concurrency.AppExecutorUtil
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * File logger with rotation support.
 * Rotates log files when they exceed maxFileSizeBytes.
 * Keeps up to maxFiles log files.
 *
 * Lines are buffered, and written within [FLUSH_DELAY_MS] of being logged,
 * or at once when [write] is asked to flush.
 */
class FileLogger(
    private val logDirectory: Path,
    // 10 MB
    private val maxFileSizeBytes: Long = 10 * 1024 * 1024,
    private val maxFiles: Int = 5,
) {
    private val lock = ReentrantLock()
    private var currentLogFile: File
    private var currentWriter: BufferedWriter?

    // The current file's length, counted as lines are written rather than asked of the file system.
    private var currentSize = 0L
    private var flushScheduled = false

    // Once closed, as the IDE exits, there may be no time left for a flush later.
    private var closed = false

    init {
        // Ensure log directory exists
        Files.createDirectories(logDirectory)

        // Initialize current log file
        currentLogFile = getLogFile(0)
        currentWriter = null
    }

    /**
     * Write a log message to file, and with [flush] everything written so far.
     */
    fun write(
        message: String,
        flush: Boolean = false,
    ) {
        lock.withLock {
            try {
                // Lazy initialize writer
                val writer =
                    currentWriter ?: BufferedWriter(FileWriter(currentLogFile, true)).also {
                        currentWriter = it
                        currentSize = currentLogFile.length()
                    }

                writer.write(message)
                writer.newLine()
                currentSize += message.length + 1
                if (flush || closed) writer.flush() else scheduleFlush()

                if (currentSize >= maxFileSizeBytes) {
                    rotate()
                }
            } catch (e: Exception) {
                // Fail silently to avoid breaking the application
                System.err.println("Failed to write to log file: ${e.message}")
            }
        }
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                lock.withLock {
                    flushScheduled = false
                    runCatching { currentWriter?.flush() }
                }
            },
            FLUSH_DELAY_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * Rotate log files.
     */
    private fun rotate() {
        try {
            // Close current writer
            currentWriter?.close()
            currentWriter = null

            // Delete oldest log file if it exists
            val oldestFile = getLogFile(maxFiles - 1)
            if (oldestFile.exists()) {
                oldestFile.delete()
            }

            // Rotate existing log files
            for (i in (maxFiles - 2) downTo 0) {
                val oldFile = getLogFile(i)
                val newFile = getLogFile(i + 1)
                if (oldFile.exists()) {
                    oldFile.renameTo(newFile)
                }
            }

            // Create new current log file
            currentLogFile = getLogFile(0)
        } catch (e: Exception) {
            System.err.println("Failed to rotate log files: ${e.message}")
        }
    }

    /**
     * Get log file for a given index.
     */
    private fun getLogFile(index: Int): File {
        val filename =
            if (index == 0) {
                "circleci.log"
            } else {
                "circleci.log.$index"
            }
        return logDirectory.resolve(filename).toFile()
    }

    /**
     * Close the logger and release resources. Anything written after is
     * written at once.
     */
    fun close() {
        lock.withLock {
            closed = true
            currentWriter?.close()
            currentWriter = null
        }
    }

    /**
     * Get the current log file path.
     */
    fun getCurrentLogFilePath(): String {
        return currentLogFile.absolutePath
    }

    /**
     * Clear all log files.
     */
    fun clearLogs() {
        lock.withLock {
            currentWriter?.close()
            currentWriter = null

            for (i in 0 until maxFiles) {
                val logFile = getLogFile(i)
                if (logFile.exists()) {
                    logFile.delete()
                }
            }

            currentLogFile = getLogFile(0)
        }
    }

    companion object {
        const val FLUSH_DELAY_MS = 1_000L

        /**
         * Get default log directory path.
         */
        fun getDefaultLogDirectory(): Path {
            val userHome = System.getProperty("user.home")
            return Paths.get(userHome, ".circleci-plugin", "logs")
        }
    }
}
