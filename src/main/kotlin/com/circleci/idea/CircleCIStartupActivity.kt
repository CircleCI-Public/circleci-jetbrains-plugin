package com.circleci.idea

import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.logging.CircleCILogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Startup activity to initialize CircleCI services when project opens.
 */
class CircleCIStartupActivity : ProjectActivity {
    private val logger = CircleCILogger.getInstance()

    override suspend fun execute(project: Project) {
        logger.logLifecycleEvent("CircleCI plugin starting for project: ${project.name}")
        // Read the stored token here, off the EDT, so the actions and settings asking for it later needn't.
        CircleCIAuthService.getInstance(project).getToken()

        logger.info("CircleCI plugin initialized successfully")
    }
}
