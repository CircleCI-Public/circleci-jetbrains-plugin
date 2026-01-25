package com.circleci.idea.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget

/**
 * Presentation for the CircleCI status bar widget.
 */
class CircleCIStatusBarPresentation(private val project: Project) : StatusBarWidget.TextPresentation {
    override fun getText(): String {
        return "CircleCI: Ready"
    }

    override fun getTooltipText(): String {
        return "Click to open CircleCI panel"
    }

    override fun getClickConsumer(): Nothing? {
        return null
    }

    override fun getAlignment(): Float {
        return 0.0f
    }
}
