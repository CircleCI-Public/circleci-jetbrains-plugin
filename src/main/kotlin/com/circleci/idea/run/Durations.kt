package com.circleci.idea.run

import java.time.Duration
import java.time.Instant

/**
 * Format an elapsed time compactly (e.g., "1h 5m", "3m 20s", "45s").
 */
fun formatElapsed(duration: Duration): String {
    val elapsed = if (duration.isNegative) Duration.ZERO else duration
    return when {
        elapsed.toHours() > 0 -> "${elapsed.toHours()}h ${elapsed.toMinutesPart()}m"
        elapsed.toMinutes() > 0 -> "${elapsed.toMinutes()}m ${elapsed.toSecondsPart()}s"
        else -> "${elapsed.toSeconds()}s"
    }
}

/**
 * How long something started at [startedAt] ran: until [endedAt], or until
 * now while it's still going. Null if it hasn't started.
 */
fun elapsedSince(
    startedAt: Instant?,
    endedAt: Instant?,
): String? = startedAt?.let { formatElapsed(Duration.between(it, endedAt ?: Instant.now())) }
