package com.circleci.idea.run

/**
 * Display status of a run, workflow, job or step, derived from the V3 API's
 * phase / outcome / current_outcome triple as `circleci` derives it (its
 * `phaseOutcomeParts`), plus on hold for approval jobs.
 *
 * [token] matches the V2-style status words the rest of the plugin (icons,
 * filters) already speaks.
 */
enum class RunStatus(val token: String, val label: String) {
    CREATED("created", "created"),
    QUEUED("queued", "queued"),
    RUNNING("running", "running"),
    FAILING("failing", "failing"),
    ERRORING("erroring", "erroring"),
    CANCELING("canceling", "canceling"),
    ON_HOLD("on_hold", "on hold"),
    SUCCESS("success", "succeeded"),
    FAILED("failed", "failed"),
    CANCELED("canceled", "canceled"),
    ERROR("error", "errored"),
    INFRASTRUCTURE_FAIL("infrastructure_fail", "infrastructure failure"),
    TIMED_OUT("timedout", "timed out"),
    NOT_RUN("not_run", "not run"),
    UNAUTHORIZED("unauthorized", "unauthorized"),
    UNKNOWN("unknown", "unknown"),
    ;

    /** True while the run/workflow/job may still change state. */
    val isActive: Boolean
        get() = this in ACTIVE

    /** True when a workflow in this state can be rerun from the start. */
    val isRerunnable: Boolean
        get() = !isActive || this == FAILING || this == ERRORING

    /** True when a workflow in this state can be canceled. */
    val isCancelable: Boolean
        get() = isActive && this != CANCELING

    /** True when the outcome counts as a failure. */
    val isFailure: Boolean
        get() = this in FAILURES

    companion object {
        private val ACTIVE = setOf(CREATED, QUEUED, RUNNING, FAILING, ERRORING, CANCELING, ON_HOLD)
        private val FAILURES = setOf(FAILING, ERRORING, FAILED, ERROR, INFRASTRUCTURE_FAIL, TIMED_OUT)

        /**
         * Derive a status from a V3 phase and outcome.
         *
         * An ended run reports only current_outcome, never outcome (a rerun can
         * still change it), so the outcome falls back to current_outcome.
         */
        fun fromV3(
            phase: String?,
            outcome: String?,
            currentOutcome: String?,
        ): RunStatus {
            return when (phase) {
                "created" -> CREATED
                "queued" -> QUEUED
                "on_hold" -> ON_HOLD
                "started" ->
                    when (currentOutcome) {
                        "failed" -> FAILING
                        "errored" -> ERRORING
                        "canceled" -> CANCELING
                        else -> RUNNING
                    }
                "ended" -> fromOutcome(outcome?.takeIf { it.isNotEmpty() } ?: currentOutcome)
                else -> UNKNOWN
            }
        }

        private fun fromOutcome(outcome: String?): RunStatus {
            return when (outcome) {
                "succeeded" -> SUCCESS
                "failed" -> FAILED
                "canceled" -> CANCELED
                "unauthorized" -> UNAUTHORIZED
                "not_run" -> NOT_RUN
                "errored" -> ERROR
                "infrastructure_fail" -> INFRASTRUCTURE_FAIL
                "timedout" -> TIMED_OUT
                else -> UNKNOWN
            }
        }
    }
}
