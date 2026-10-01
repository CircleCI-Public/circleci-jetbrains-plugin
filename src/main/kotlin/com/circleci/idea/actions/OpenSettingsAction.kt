package com.circleci.idea.actions

import com.circleci.idea.settings.CircleCIConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware

/**
 * Action to open CircleCI settings.
 */
class OpenSettingsAction :
    AnAction(
        "CircleCI Settings...",
        "Open the CircleCI settings",
        AllIcons.General.Settings,
    ),
    DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, CircleCIConfigurable::class.java)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
