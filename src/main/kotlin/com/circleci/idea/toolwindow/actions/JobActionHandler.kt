package com.circleci.idea.toolwindow.actions

import com.circleci.idea.state.JobDetailsState
import com.intellij.openapi.project.Project

/**
 * Base class for job action handlers.
 * Implements the Command pattern with availability validation for UI actions on CircleCI jobs.
 *
 * Each handler encapsulates:
 * - Availability checking (when the action can be performed)
 * - Execution logic (what happens when the action is triggered)
 * - Error handling and user feedback
 *
 * This pattern improves testability and reduces complexity in JobDetailsPanel.setupActionButtons.
 */
abstract class JobActionHandler(
    protected val project: Project,
) {
    /**
     * Execute this action with the given job details state.
     * This method is called when the user triggers the action and it's available.
     *
     * @param state Current job details state
     */
    abstract suspend fun execute(state: JobDetailsState)

    /**
     * Check if this action is available for the given state.
     * Returns ActionAvailability.Available if the action can be performed,
     * or ActionAvailability.Unavailable with a reason if it cannot.
     *
     * @param state Current job details state
     * @return Availability result with optional error message
     */
    abstract fun isAvailable(state: JobDetailsState): ActionAvailability
}

/**
 * Result of checking action availability.
 */
sealed class ActionAvailability {
    /**
     * Action is available and can be executed.
     */
    object Available : ActionAvailability()

    /**
     * Action is not available.
     *
     * @property reason Human-readable reason why the action is unavailable (shown in dialog message)
     * @property title Dialog title for the unavailability message
     */
    data class Unavailable(
        val reason: String,
        val title: String = "Action Unavailable",
    ) : ActionAvailability()
}
