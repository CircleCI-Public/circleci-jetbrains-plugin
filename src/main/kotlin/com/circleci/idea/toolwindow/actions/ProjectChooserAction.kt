package com.circleci.idea.toolwindow.actions

import com.circleci.idea.project.CircleCIProjectService
import com.circleci.idea.project.ProjectSlugs
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
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
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
                SLUG_HELP,
                "Other CircleCI Project",
                null,
                null,
                SlugValidator,
            )?.trim()
        if (slug.isNullOrEmpty()) return
        val service = project.getService(CircleCIProjectService::class.java)
        if (service.addProjectBySlug(slug)) service.linkProjectInBackground(slug)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

// The two forms a slug takes. HtmlBuilder escapes the text, which has <placeholders> in it.
private val SLUG_HELP =
    HtmlBuilder()
        .append("The project's slug, in one of two forms:")
        .br().br()
        .append(HtmlChunk.text("Standalone projects: ").bold())
        .append(HtmlChunk.text("circleci/<org-id>/<project-id>").code())
        .br()
        .append("Both IDs are UUIDs, shown in the project's settings on CircleCI.")
        .br().br()
        .append(HtmlChunk.text("Classic projects: ").bold())
        .append(HtmlChunk.text("gh/<org>/<repo>").code())
        .append(" or ")
        .append(HtmlChunk.text("bb/<org>/<repo>").code())
        .br()
        .append("Named after the GitHub or Bitbucket repository it builds, e.g. ")
        .append(HtmlChunk.text("gh/myorg/myrepo").code())
        .wrapWithHtmlBody()
        .toString()

/** Keeps OK disabled, saying why, until the slug has one of the two forms. */
private object SlugValidator : InputValidatorEx {
    override fun checkInput(inputString: String?): Boolean = ProjectSlugs.problem(inputString.orEmpty().trim()) == null

    // Nothing entered isn't worth an error yet
    override fun getErrorText(inputString: String): String? =
        inputString.trim().takeIf { it.isNotEmpty() }?.let { ProjectSlugs.problem(it) }
}
