package com.circleci.idea.actions

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.git.GitBranchService
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.project.CircleCIProjectService
import com.circleci.idea.settings.CircleCISettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Action to validate CircleCI configuration files.
 * Triggers validation via CircleCI API and displays results.
 */
class ValidateConfigAction : AnAction("Validate CircleCI Config") {
    private val logger = CircleCILogger.getInstance()
    private val apiService = CircleCIApiService.getInstance()

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val project = e.project

        // Enable only for .circleci/config.yml files
        e.presentation.isEnabledAndVisible = project != null &&
            file != null &&
            isCircleCIConfigFile(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        if (!isCircleCIConfigFile(file)) {
            return
        }

        // Save document before validation
        val document = FileDocumentManager.getInstance().getDocument(file)
        if (document != null) {
            FileDocumentManager.getInstance().saveDocument(document)
        }

        validateConfig(project, file)
    }

    private fun validateConfig(
        project: Project,
        file: VirtualFile,
    ) {
        // Validate prerequisites and get necessary data
        val validationData = validatePrerequisites(project) ?: return

        // Get current branch
        val branchService = GitBranchService.getInstance(project)
        val branch = branchService.getCurrentBranch() ?: branchService.getDefaultBranch()

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Validating CircleCI Configuration", false) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Reading configuration..."

                    val configContent =
                        try {
                            String(file.contentsToByteArray(), Charsets.UTF_8)
                        } catch (e: Exception) {
                            logger.error("Failed to read config file", e)
                            showErrorNotification(project, "Failed to read config file: ${e.message}")
                            return
                        }

                    indicator.text = "Sending to CircleCI for validation..."

                    val result =
                        apiService.validateConfig(
                            configYaml = configContent,
                            projectSlug = validationData.circleCIProject.slug,
                            branch = branch,
                        )

                    ApplicationManager.getApplication().invokeLater {
                        result.fold(
                            onSuccess = { response ->
                                if (response.valid) {
                                    showSuccessNotification(project)
                                } else {
                                    showValidationErrors(project, response.errors)
                                }
                            },
                            onFailure = { error ->
                                logger.error("Config validation failed", error)
                                showErrorNotification(project, "Validation failed: ${error.message}")
                            },
                        )
                    }
                }
            },
        )
    }

    private data class ValidationData(
        val circleCIProject: com.circleci.idea.project.models.CircleCIProject,
    )

    private fun validatePrerequisites(project: Project): ValidationData? {
        // Check authentication and get token
        val authService = CircleCIAuthService.getInstance(project)
        val token = getValidatedToken(project, authService) ?: return null

        // Initialize API service
        val settings = CircleCISettings.getInstance()
        apiService.initialize(token, settings.hostUrl)

        // Get and validate project
        val circleCIProject = getSelectedProject(project) ?: return null

        return ValidationData(circleCIProject)
    }

    private fun getValidatedToken(
        project: Project,
        authService: CircleCIAuthService,
    ): String? {
        if (!authService.isAuthenticated()) {
            showErrorNotification(
                project,
                "Please login to CircleCI first (Tools → CircleCI → Login)",
            )
            return null
        }

        val token = authService.getToken()
        if (token == null) {
            showErrorNotification(project, "No authentication token found. Please login to CircleCI.")
            return null
        }

        return token
    }

    private fun getSelectedProject(project: Project): com.circleci.idea.project.models.CircleCIProject? {
        val projectService = project.getService(CircleCIProjectService::class.java)
        val selectedProjects = projectService.getSelectedProjectObjects()

        if (selectedProjects.isEmpty()) {
            showErrorNotification(
                project,
                "No CircleCI project selected. Please select a project in the CircleCI tool window.",
            )
            return null
        }

        val circleCIProject =
            selectedProjects.firstOrNull { it.localPath == project.basePath }
                ?: selectedProjects.firstOrNull()

        if (circleCIProject == null) {
            showErrorNotification(
                project,
                "No CircleCI project found. Please set up your project in CircleCI.",
            )
            return null
        }

        return circleCIProject
    }

    private fun showSuccessNotification(project: Project) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CircleCI Notifications")
            .createNotification(
                "Configuration Valid",
                "Your CircleCI configuration is valid!",
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    private fun showValidationErrors(
        project: Project,
        errors: List<com.circleci.idea.api.models.ConfigError>,
    ) {
        val errorMessage =
            buildString {
                appendLine("Configuration validation failed with ${errors.size} error(s):")
                appendLine()
                errors.forEachIndexed { index, error ->
                    appendLine("${index + 1}. ${error.message}")
                    if (error.type != null) {
                        appendLine("   Type: ${error.type}")
                    }
                }
            }

        NotificationGroupManager.getInstance()
            .getNotificationGroup("CircleCI Notifications")
            .createNotification(
                "Configuration Invalid",
                errorMessage,
                NotificationType.ERROR,
            )
            .notify(project)
    }

    private fun showErrorNotification(
        project: Project,
        message: String,
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CircleCI Notifications")
            .createNotification(
                "Validation Error",
                message,
                NotificationType.ERROR,
            )
            .notify(project)
    }

    private fun isCircleCIConfigFile(file: VirtualFile): Boolean {
        val path = file.path
        return path.contains(".circleci") && file.name == "config.yml"
    }
}
