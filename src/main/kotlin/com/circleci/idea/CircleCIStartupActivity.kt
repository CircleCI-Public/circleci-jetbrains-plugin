package com.circleci.idea

import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.notifications.CircleCINotificationService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Startup activity to initialize CircleCI services when project opens.
 */
class CircleCIStartupActivity : ProjectActivity {
    private val logger = CircleCILogger.getInstance()

    override suspend fun execute(project: Project) {
        logger.logLifecycleEvent("CircleCI plugin starting for project: ${project.name}")

        // Initialize notification service
        // This will start observing WebSocket events
        CircleCINotificationService.getInstance(project)

        logger.info("CircleCI plugin initialized successfully")
    }
}
