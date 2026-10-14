package com.circleci.idea.toolwindow.actions

import com.circleci.idea.git.GitBranchService
import com.circleci.idea.project.CircleCIProjectService
import com.circleci.idea.toolwindow.tree.CircleCITreeModel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.awt.RelativePoint
import javax.swing.JComponent

/**
 * Select the run of the commit checked out, as the Project view's crosshair
 * selects the file open; saying so when none of the runs listed is of it.
 */
class LocateCurrentRunAction(private val model: CircleCITreeModel) :
    AnAction(
        "Locate Current Commit's Run",
        "Select the run of the commit checked out",
        AllIcons.General.Locate,
    ),
    DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repositoryPath = project.getService(CircleCIProjectService::class.java).getSelectedProject()?.localPath
        val revision = GitBranchService.getInstance(project).getCurrentRevision(repositoryPath)
        val message =
            when {
                revision == null -> "No commit checked out"
                model.locateRun(revision) -> return
                else -> "No run found for ${revision.take(SHORT_REVISION_LENGTH)}"
            }

        // Beside the button; failing that, by the tool window's.
        val button = e.inputEvent?.component as? JComponent
        if (button == null) {
            ToolWindowManager.getInstance(project).notifyByBalloon(TOOL_WINDOW_ID, MessageType.INFO, message)
            return
        }
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(message, MessageType.INFO, null)
            .setFadeoutTime(FADEOUT_MS)
            .createBalloon()
            .show(RelativePoint.getSouthOf(button), Balloon.Position.below)
    }

    // The tree's state is the EDT's.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    private companion object {
        const val TOOL_WINDOW_ID = "CircleCI"
        const val SHORT_REVISION_LENGTH = 7
        const val FADEOUT_MS = 3000L
    }
}
