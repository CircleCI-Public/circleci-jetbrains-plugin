package com.circleci.idea.logging

/**
 * Utility for redacting sensitive data from log messages.
 * Prevents tokens, API keys, and other sensitive information from being logged.
 */
object SensitiveDataRedactor {

    private val TOKEN_PATTERN = Regex("""(?i)(circle[_-]?token)\s*[:=]\s*([^\s,}"']+)""")
    private val AUTHORIZATION_PATTERN = Regex("""(?i)(authorization)\s*:\s*(bearer\s+)?([^\s,}"']+)""")
    private val API_KEY_PATTERN = Regex("""(?i)(api[_-]?key|apikey)\s*[:=]\s*([^\s,}"']+)""")
    private val PASSWORD_PATTERN = Regex("""(?i)(password|passwd|pwd)\s*[:=]\s*([^\s,}"']+)""")
    private val HEADER_TOKEN_PATTERN = Regex("""Circle-Token:\s*([^\s,}"']+)""", RegexOption.IGNORE_CASE)

    // Patterns for long alphanumeric strings that might be tokens
    private val LONG_ALNUM_PATTERN = Regex("""[a-zA-Z0-9]{32,}""")

    /**
     * Redact sensitive data from a log message.
     */
    fun redact(message: String): String {
        var redacted = message

        // Redact known patterns
        redacted = TOKEN_PATTERN.replace(redacted) { matchResult ->
            val prefix = matchResult.groupValues[1]
            val token = matchResult.groupValues[2]
            "$prefix: ${maskToken(token)}"
        }

        redacted = AUTHORIZATION_PATTERN.replace(redacted) { matchResult ->
            val prefix = matchResult.groupValues[1]
            val bearer = matchResult.groupValues[2]
            val token = matchResult.groupValues[3]
            if (bearer.isNotBlank()) {
                "$prefix: ${bearer}${maskToken(token)}"
            } else {
                "$prefix: ${maskToken(token)}"
            }
        }

        redacted = API_KEY_PATTERN.replace(redacted) { matchResult ->
            val prefix = matchResult.groupValues[1]
            val key = matchResult.groupValues[2]
            "$prefix: ${maskToken(key)}"
        }

        redacted = PASSWORD_PATTERN.replace(redacted) { matchResult ->
            val prefix = matchResult.groupValues[1]
            "$prefix: **********"
        }

        redacted = HEADER_TOKEN_PATTERN.replace(redacted) { matchResult ->
            val token = matchResult.groupValues[1]
            "Circle-Token: ${maskToken(token)}"
        }

        return redacted
    }

    /**
     * Redact all occurrences of a specific token value.
     */
    fun redactToken(message: String, token: String?): String {
        if (token.isNullOrBlank()) return message
        return message.replace(token, maskToken(token))
    }

    /**
     * Mask a token by showing only the last 4 characters.
     */
    private fun maskToken(token: String): String {
        return when {
            token.length <= 4 -> "****"
            token.length <= 8 -> "****${token.takeLast(4)}"
            else -> "***${token.takeLast(4)}"
        }
    }

    /**
     * Redact sensitive data from exception stack traces.
     */
    fun redactStackTrace(throwable: Throwable): String {
        val stackTrace = throwable.stackTraceToString()
        return redact(stackTrace)
    }

    /**
     * Redact a map of key-value pairs (e.g., HTTP headers).
     */
    fun redactMap(map: Map<String, String>): Map<String, String> {
        return map.mapValues { (key, value) ->
            when {
                key.contains("token", ignoreCase = true) -> maskToken(value)
                key.contains("authorization", ignoreCase = true) -> maskToken(value)
                key.contains("api", ignoreCase = true) && key.contains("key", ignoreCase = true) -> maskToken(value)
                key.contains("password", ignoreCase = true) -> "**********"
                else -> value
            }
        }
    }
}
