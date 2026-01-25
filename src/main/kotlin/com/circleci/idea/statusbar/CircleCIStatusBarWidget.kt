package com.circleci.idea.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.widget.StatusBarEditorBasedWidgetFactory

/**
 * Status bar widget for CircleCI.
 */
class CircleCIStatusBarWidget(private val project: Project) : StatusBarWidget {
    override fun ID(): String = "CircleCIStatusBar"

    override fun getPresentation(): StatusBarWidget.WidgetPresentation? {
        return CircleCIStatusBarPresentation(project)
    }

    override fun dispose() {
        // Cleanup if needed
    }
}
