package com.circleci.idea.logging

import java.text.SimpleDateFormat
import java.util.Date

/**
 * Formats log messages with timestamp, level, and context.
 */
object LogFormatter {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")

    /**
     * Format a log message for file output.
     */
    fun formatForFile(
        level: LogLevel,
        message: String,
        context: LogContext? = null,
        throwable: Throwable? = null,
    ): String {
        val timestamp = dateFormat.format(Date())
        val levelStr = level.displayName.padEnd(5)

        val builder = StringBuilder()
        builder.append("[$timestamp] [$levelStr] $message")

        if (context != null) {
            builder.append(" [${context.extensionVersion}]")
        }

        if (throwable != null) {
            builder.append("\n")
            builder.append(SensitiveDataRedactor.redactStackTrace(throwable))
        }

        return builder.toString()
    }

    /**
     * Format a log message for console output (IDE log).
     */
    fun formatForConsole(
        @Suppress("UNUSED_PARAMETER") _level: LogLevel,
        message: String,
        throwable: Throwable? = null,
    ): String {
        val builder = StringBuilder()
        builder.append("[CircleCI] ")
        builder.append(message)

        if (throwable != null) {
            builder.append(": ")
            builder.append(throwable.message ?: throwable::class.simpleName)
        }

        return builder.toString()
    }

    /**
     * Format API request details for logging.
     */
    fun formatApiRequest(
        method: String,
        url: String,
        statusCode: Int? = null,
    ): String {
        return if (statusCode != null) {
            "API $method $url -> $statusCode"
        } else {
            "API $method $url"
        }
    }

    /**
     * Format WebSocket event for logging.
     */
    fun formatWebSocketEvent(
        event: String,
        channel: String? = null,
    ): String {
        return if (channel != null) {
            "WebSocket [$channel] $event"
        } else {
            "WebSocket $event"
        }
    }

    /**
     * Format state change for logging.
     */
    fun formatStateChange(
        stateName: String,
        from: Any?,
        to: Any?,
    ): String {
        return "State[$stateName] $from -> $to"
    }
}
