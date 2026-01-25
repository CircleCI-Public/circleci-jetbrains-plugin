package com.circleci.idea.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/**
 * Factory for creating the CircleCI status bar widget.
 */
class CircleCIStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "CircleCIStatusBar"

    override fun getDisplayName(): String = "CircleCI"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return CircleCIStatusBarWidget(project)
    }

    override fun disposeWidget(widget: StatusBarWidget) {
        // Cleanup if needed
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
