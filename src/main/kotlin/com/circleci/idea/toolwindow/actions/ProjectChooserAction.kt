package com.circleci.idea.toolwindow.actions

import com.circleci.idea.project.CircleCIProjectService
import com.circleci.idea.project.models.CircleCIProject
import com.circleci.idea.run.RunScope
import com.circleci.idea.state.CircleCIStateStore
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import javax.swing.JComponent

/**
 * The project whose runs the tool window lists, in its title bar: one of
 * those found in the workspace, or another entered by slug. Choosing one links
 * the workspace to it in `.circleci/info.yml`, as `circleci project link` does.
 * "My runs" spans every project, so the choice doesn't apply to it.
 */
class ProjectChooserAction : ComboBoxAction(), DumbAware {
    override fun createPopupActionGroup(
        button: JComponent,
        dataContext: DataContext,
    ): DefaultActionGroup {
        val group = DefaultActionGroup()
        val project = dataContext.getData(CommonDataKeys.PROJECT) ?: return group
        val projects = project.getService(CircleCIProjectService::class.java).getAllProjects()
        projects.forEach { group.add(SelectProjectAction(it)) }
        group.addSeparator()
        group.add(OtherProjectAction())
        return group
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }
        if (CircleCIStateStore.getInstance(project).filters.value.scope == RunScope.MY_RUNS) {
            e.presentation.text = "All projects"
            e.presentation.description = "My runs spans every project"
            e.presentation.isEnabled = false
            return
        }
        val selected = project.getService(CircleCIProjectService::class.java).getSelectedProject()
        e.presentation.text = selected?.getDisplayName() ?: "No project"
        e.presentation.description = "The project whose runs to list"
        e.presentation.isEnabled = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

private class SelectProjectAction(private val circleCIProject: CircleCIProject) :
    ToggleAction(circleCIProject.getDisplayName()), DumbAware {
    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return project.getService(CircleCIProjectService::class.java).selectedProject.value == circleCIProject.slug
    }

    override fun setSelected(
        e: AnActionEvent,
        state: Boolean,
    ) {
        val project = e.project ?: return
        val service = project.getService(CircleCIProjectService::class.java)
        service.selectProject(circleCIProject.slug)
        service.linkProjectInBackground(circleCIProject.slug)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/** List another project's runs, entered by its slug. */
private class OtherProjectAction : AnAction("Other Project..."), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val slug =
            Messages.showInputDialog(
                project,
                "The CircleCI project's slug, as vcs/org/repo (e.g., gh/myorg/myrepo), " +
                    "or circleci/<org-id>/<project-id> for a standalone project:",
                "Other CircleCI Project",
                null,
            )?.trim()
        if (slug.isNullOrEmpty()) return
        val service = project.getService(CircleCIProjectService::class.java)
        if (service.addProjectBySlug(slug)) {
            service.linkProjectInBackground(slug)
        } else {
            Messages.showErrorDialog(
                project,
                "\"$slug\" isn't a project slug: use vcs/org/repo, e.g. gh/myorg/myrepo",
                "Invalid Project Slug",
            )
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
