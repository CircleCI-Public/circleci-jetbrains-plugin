package com.circleci.idea.run

import java.time.Duration
import java.time.Instant

/**
 * Which runs the CircleCI tool window lists — the "trigger" scope of `circleci run get`.
 */
enum class RunScope(val label: String) {
    CURRENT_BRANCH("Current branch"),
    DEFAULT_BRANCH("Default branch"),
    ALL_BRANCHES("All branches"),

    /** The authenticated user's runs, across every project. */
    MY_RUNS("My runs"),
}

/**
 * A status the run list can be narrowed to.
 *
 * [token] is the `pipeline.status` value the runs/search filter accepts. The
 * my-runs endpoint has no such filter; it filters on the run's own phase and
 * current_outcome instead, given by [phase] and [currentOutcome].
 */
enum class RunStatusFilter(
    val token: String,
    val label: String,
    val phase: String,
    val currentOutcome: String?,
) {
    CANCELED("canceled", "Canceled", "ended", "canceled"),
    ERROR("error", "Error", "ended", "errored"),
    FAILED("failed", "Failed", "ended", "failed"),
    FAILING("failing", "Failing", "started", "failed"),
    NOT_RUN("not_run", "Not run", "ended", "not_run"),
    QUEUED("queued", "Queued", "queued", null),
    RUNNING("running", "Running", "started", null),
    SUCCESS("success", "Success", "ended", "succeeded"),
    UNAUTHORIZED("unauthorized", "Unauthorized", "ended", "unauthorized"),
}

/** The relative ages the Created filter offers, measured back from now. */
enum class CreatedAge(val label: String, val duration: Duration) {
    HOUR_1("1 hour", Duration.ofHours(1)),
    HOURS_6("6 hours", Duration.ofHours(6)),
    HOURS_12("12 hours", Duration.ofHours(12)),
    HOURS_24("24 hours", Duration.ofHours(24)),
    DAYS_7("7 days", Duration.ofDays(7)),
    WEEKS_2("2 weeks", Duration.ofDays(14)),
    MONTH_1("1 month", Duration.ofDays(30)),
}

/** Runs created more recently than [age] when [newer], otherwise longer ago. */
data class CreatedFilter(
    val age: CreatedAge,
    val newer: Boolean,
) {
    val label: String
        get() = "${if (newer) "Newer" else "Older"} than ${age.label}"
}

/** A time window to list runs in. */
data class RunWindow(val from: Instant, val to: Instant)

object RunQueries {
    /** How far back runs/search looks; it requires a bounded window. */
    val SEARCH_HORIZON: Duration = Duration.ofDays(90)

    /** The largest page the runs endpoints accept. */
    const val MAX_PAGE_SIZE = 20

    /**
     * The window a [created] filter selects. Without one, the full search
     * horizon up to [now]. "Older than" is floored at the horizon, which is
     * always well beyond the longest [CreatedAge].
     */
    fun window(
        created: CreatedFilter?,
        now: Instant,
    ): RunWindow {
        val horizon = now.minus(SEARCH_HORIZON)
        if (created == null) {
            return RunWindow(horizon, now)
        }
        val cut = now.minus(created.age.duration)
        return if (created.newer) RunWindow(cut, now) else RunWindow(horizon, cut)
    }

    /** The runs/search filter expression for a branch and status; either may be absent. */
    fun filterExpression(
        branch: String?,
        status: RunStatusFilter?,
    ): String {
        val parts = mutableListOf<String>()
        if (!branch.isNullOrEmpty()) {
            parts.add("pipeline.git.branch == ${quote(branch)}")
        }
        if (status != null) {
            parts.add("pipeline.status == ${quote(status.token)}")
        }
        return parts.joinToString(" and ")
    }

    private fun quote(value: String): String {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
