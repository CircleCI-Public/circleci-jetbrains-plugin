package com.circleci.idea.logging

import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * File logger with rotation support.
 * Rotates log files when they exceed maxFileSizeBytes.
 * Keeps up to maxFiles log files.
 */
class FileLogger(
    private val logDirectory: Path,
    // 10 MB
    private val maxFileSizeBytes: Long = 10 * 1024 * 1024,
    private val maxFiles: Int = 5,
) {
    private val lock = ReentrantLock()
    private var currentLogFile: File
    private var currentWriter: PrintWriter?

    init {
        // Ensure log directory exists
        Files.createDirectories(logDirectory)

        // Initialize current log file
        currentLogFile = getLogFile(0)
        currentWriter = null
    }

    /**
     * Write a log message to file.
     */
    fun write(message: String) {
        lock.withLock {
            try {
                // Check if rotation is needed
                if (currentLogFile.length() >= maxFileSizeBytes) {
                    rotate()
                }

                // Lazy initialize writer
                if (currentWriter == null) {
                    currentWriter = PrintWriter(FileWriter(currentLogFile, true))
                }

                currentWriter?.println(message)
                currentWriter?.flush()
            } catch (e: Exception) {
                // Fail silently to avoid breaking the application
                System.err.println("Failed to write to log file: ${e.message}")
            }
        }
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
     * Close the logger and release resources.
     */
    fun close() {
        lock.withLock {
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
        /**
         * Get default log directory path.
         */
        fun getDefaultLogDirectory(): Path {
            val userHome = System.getProperty("user.home")
            return Paths.get(userHome, ".circleci-plugin", "logs")
        }
    }
}
