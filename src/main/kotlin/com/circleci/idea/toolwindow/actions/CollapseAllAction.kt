package com.circleci.idea.toolwindow.actions

import com.circleci.idea.toolwindow.tree.CircleCITreeModel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/** Close every run, workflow and job open in the run tree. */
class CollapseAllAction(private val model: CircleCITreeModel) :
    AnAction(
        "Collapse All",
        "Collapse every run in the tree",
        AllIcons.Actions.Collapseall,
    ),
    DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        model.collapseAll()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = model.canCollapse()
    }

    // The tree's state is the EDT's.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
