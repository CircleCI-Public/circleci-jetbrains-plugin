package com.circleci.idea.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Factory for creating the CircleCI tool window.
 */
class CircleCIToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        com.circleci.idea.logging.CircleCILogger.getInstance().info("Creating CircleCI tool window content")

        // Register tool window with service
        val toolWindowService = project.getService(CircleCIToolWindowService::class.java)
        toolWindowService.setToolWindow(toolWindow)

        val circleCIToolWindow = CircleCIToolWindowContent(project)
        val contentPanel = circleCIToolWindow.getContent()
        com.circleci.idea.logging.CircleCILogger.getInstance().info("Content panel: $contentPanel, components: ${contentPanel.componentCount}")

        val content = ContentFactory.getInstance().createContent(
            contentPanel,
            "Pipelines",
            false
        )
        toolWindow.contentManager.addContent(content)
        com.circleci.idea.logging.CircleCILogger.getInstance().info("Tool window content added")
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
