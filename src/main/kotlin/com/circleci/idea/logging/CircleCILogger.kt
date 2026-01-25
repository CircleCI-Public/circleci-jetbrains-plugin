package com.circleci.idea.logging

import com.circleci.idea.settings.CircleCISettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Main logger for CircleCI plugin.
 *
 * Features:
 * - Multiple log levels (DEBUG, INFO, WARN, ERROR)
 * - Logs to IDE log (visible in IDE's log viewer)
 * - Logs to file with rotation (10MB max, keep last 5 files)
 * - Automatic redaction of tokens and sensitive data
 * - Includes context information (extension version, IDE version, platform)
 * - Configurable log level via settings
 */
class CircleCILogger private constructor() {

    private val ideLogger = Logger.getInstance("CircleCI")
    private val fileLogger: FileLogger
    private val context: LogContext

    init {
        fileLogger = FileLogger(
            logDirectory = FileLogger.getDefaultLogDirectory(),
            maxFileSizeBytes = 10 * 1024 * 1024, // 10 MB
            maxFiles = 5
        )
        context = LogContext.create()

        // Log initialization
        info("CircleCI plugin initialized")
        info("Extension version: ${context.extensionVersion}")
        info("IDE version: ${context.ideVersion}")
        info("Platform: ${context.platform}")
        info("Log file: ${fileLogger.getCurrentLogFilePath()}")
    }

    /**
     * Get current log level from settings.
     */
    private fun getCurrentLogLevel(): LogLevel {
        return try {
            val settings = CircleCISettings.getInstance()
            LogLevel.fromString(settings.logLevel)
        } catch (e: Exception) {
            LogLevel.INFO
        }
    }

    /**
     * Log a debug message.
     */
    fun debug(message: String, throwable: Throwable? = null) {
        log(LogLevel.DEBUG, message, throwable)
    }

    /**
     * Log an info message.
     */
    fun info(message: String, throwable: Throwable? = null) {
        log(LogLevel.INFO, message, throwable)
    }

    /**
     * Log a warning message.
     */
    fun warn(message: String, throwable: Throwable? = null) {
        log(LogLevel.WARN, message, throwable)
    }

    /**
     * Log an error message.
     */
    fun error(message: String, throwable: Throwable? = null) {
        log(LogLevel.ERROR, message, throwable)
    }

    /**
     * Log a message at the specified level.
     */
    private fun log(level: LogLevel, message: String, throwable: Throwable? = null) {
        val currentLevel = getCurrentLogLevel()

        // Check if we should log this level
        if (!level.shouldLog(currentLevel)) {
            return
        }

        // Redact sensitive data
        val redactedMessage = SensitiveDataRedactor.redact(message)

        // Log to IDE log
        logToIde(level, redactedMessage, throwable)

        // Log to file
        logToFile(level, redactedMessage, throwable)
    }

    /**
     * Log to IDE's built-in logger.
     */
    private fun logToIde(level: LogLevel, message: String, throwable: Throwable?) {
        val formattedMessage = LogFormatter.formatForConsole(level, message, throwable)

        when (level) {
            LogLevel.DEBUG -> ideLogger.debug(formattedMessage, throwable)
            LogLevel.INFO -> ideLogger.info(formattedMessage, throwable)
            LogLevel.WARN -> ideLogger.warn(formattedMessage, throwable)
            LogLevel.ERROR -> ideLogger.error(formattedMessage, throwable)
        }
    }

    /**
     * Log to file.
     */
    private fun logToFile(level: LogLevel, message: String, throwable: Throwable?) {
        val formattedMessage = LogFormatter.formatForFile(level, message, context, throwable)
        fileLogger.write(formattedMessage)
    }

    /**
     * Log API request.
     */
    fun logApiRequest(method: String, url: String) {
        val message = LogFormatter.formatApiRequest(method, url)
        debug(message)
    }

    /**
     * Log API response.
     */
    fun logApiResponse(method: String, url: String, statusCode: Int, durationMs: Long) {
        val message = "${LogFormatter.formatApiRequest(method, url, statusCode)} (${durationMs}ms)"
        debug(message)
    }

    /**
     * Log API error.
     */
    fun logApiError(method: String, url: String, statusCode: Int, errorMessage: String) {
        val message = "${LogFormatter.formatApiRequest(method, url, statusCode)}: $errorMessage"
        error(message)
    }

    /**
     * Log WebSocket event.
     */
    fun logWebSocketEvent(event: String, channel: String? = null) {
        val message = LogFormatter.formatWebSocketEvent(event, channel)
        debug(message)
    }

    /**
     * Log state change (only at DEBUG level).
     */
    fun logStateChange(stateName: String, from: Any?, to: Any?) {
        val message = LogFormatter.formatStateChange(stateName, from, to)
        debug(message)
    }

    /**
     * Log user action.
     */
    fun logUserAction(action: String, details: String? = null) {
        val message = if (details != null) {
            "User action: $action - $details"
        } else {
            "User action: $action"
        }
        info(message)
    }

    /**
     * Log extension lifecycle event.
     */
    fun logLifecycleEvent(event: String) {
        info("Lifecycle: $event")
    }

    /**
     * Show error notification to user and log it.
     */
    fun notifyError(project: Project?, title: String, message: String, throwable: Throwable? = null) {
        error("$title: $message", throwable)

        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CircleCI Notifications")
                .createNotification(title, message, NotificationType.ERROR)
                .notify(project)
        } catch (e: Exception) {
            // If notification fails, just log it
            error("Failed to show notification", e)
        }
    }

    /**
     * Clear all log files.
     */
    fun clearLogs() {
        info("Clearing log files")
        fileLogger.clearLogs()
        info("Log files cleared")
    }

    /**
     * Get current log file path.
     */
    fun getLogFilePath(): String {
        return fileLogger.getCurrentLogFilePath()
    }

    /**
     * Close logger and release resources.
     */
    fun close() {
        info("CircleCI plugin shutting down")
        fileLogger.close()
    }

    companion object {
        @Volatile
        private var instance: CircleCILogger? = null

        /**
         * Get singleton instance of logger.
         */
        fun getInstance(): CircleCILogger {
            return instance ?: synchronized(this) {
                instance ?: CircleCILogger().also { instance = it }
            }
        }
    }
}
