package com.circleci.idea.logging

/**
 * Log levels for CircleCI plugin logging.
 */
enum class LogLevel(val value: Int, val displayName: String) {
    DEBUG(0, "DEBUG"),
    INFO(1, "INFO"),
    WARN(2, "WARN"),
    ERROR(3, "ERROR"),
    ;

    companion object {
        fun fromString(level: String): LogLevel {
            return values().firstOrNull { it.displayName.equals(level, ignoreCase = true) }
                ?: INFO
        }
    }

    fun shouldLog(targetLevel: LogLevel): Boolean {
        return this.value >= targetLevel.value
    }
}
