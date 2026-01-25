package com.circleci.idea.toolwindow.actions

import com.circleci.idea.project.CircleCIProjectService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages

/**
 * Action to manually add a CircleCI project by slug.
 */
class AddProjectAction : AnAction(
    "Add Project",
    "Manually add a CircleCI project by slug (e.g., gh/org/repo)",
    AllIcons.General.Add
), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectService = project.getService(CircleCIProjectService::class.java)

        val slug = Messages.showInputDialog(
            project,
            "Enter the CircleCI project slug:\n(Format: vcs/org/repo, e.g., gh/myorg/myrepo)",
            "Add CircleCI Project",
            Messages.getQuestionIcon()
        )

        if (slug != null && slug.isNotBlank()) {
            val success = projectService.addProjectBySlug(slug.trim())
            if (success) {
                Messages.showInfoMessage(
                    project,
                    "Project '$slug' has been added and selected.",
                    "Project Added"
                )
            } else {
                Messages.showErrorDialog(
                    project,
                    "Invalid project slug format. Please use: vcs/org/repo\n" +
                            "Examples:\n" +
                            "  gh/myorg/myrepo (GitHub)\n" +
                            "  bb/myorg/myrepo (Bitbucket)\n" +
                            "  gl/myorg/myrepo (GitLab)",
                    "Invalid Project Slug"
                )
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null
    }
}
