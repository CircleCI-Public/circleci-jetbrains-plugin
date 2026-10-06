package com.circleci.idea.toolwindow.actions

import com.circleci.idea.polling.RunPollingService
import com.circleci.idea.settings.CircleCISettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware

/**
 * Action to toggle auto-refresh for runs.
 * When enabled, runs are automatically polled at adaptive intervals.
 */
class ToggleAutoRefreshAction :
    ToggleAction(
        "Auto-Refresh",
        "Automatically refresh runs (30s for recent, 2m for older)",
        AllIcons.General.InspectionsEye,
    ),
    DumbAware {
    private val settings = CircleCISettings.getInstance()

    override fun isSelected(e: AnActionEvent): Boolean {
        return settings.autoRefreshEnabled
    }

    override fun setSelected(
        e: AnActionEvent,
        state: Boolean,
    ) {
        // The setting is the IDE's, not the project's: every open project follows it.
        settings.autoRefreshEnabled = state
        RunPollingService.applySettingsEverywhere()
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project
        e.presentation.isEnabled = project != null
    }
}
