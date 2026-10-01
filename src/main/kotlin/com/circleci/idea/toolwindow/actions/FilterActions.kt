package com.circleci.idea.toolwindow.actions

import com.circleci.idea.git.GitBranchService
import com.circleci.idea.project.CircleCIProjectService
import com.circleci.idea.run.CreatedAge
import com.circleci.idea.run.CreatedFilter
import com.circleci.idea.run.RunScope
import com.circleci.idea.run.RunStatusFilter
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.state.FiltersState
import com.circleci.idea.toolwindow.CircleCIToolWindowService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import javax.swing.JComponent

/**
 * Base for the run list's filter combo boxes.
 */
abstract class RunFilterComboAction : ComboBoxAction(), DumbAware {
    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }
        e.presentation.text = text(project, CircleCIStateStore.getInstance(project).filters.value)
        e.presentation.isEnabled = true
    }

    protected abstract fun text(
        project: Project,
        filters: FiltersState,
    ): String

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Base for a single filter choice: checked when [isSelected], and applying
 * [apply] to the filters when chosen.
 */
abstract class SetRunFilterAction(text: String) : ToggleAction(text), DumbAware {
    protected abstract fun isSelected(filters: FiltersState): Boolean

    protected abstract fun apply(filters: FiltersState): FiltersState

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return isSelected(CircleCIStateStore.getInstance(project).filters.value)
    }

    override fun setSelected(
        e: AnActionEvent,
        state: Boolean,
    ) {
        val project = e.project ?: return
        val stateStore = CircleCIStateStore.getInstance(project)
        val updated = apply(stateStore.filters.value)
        if (updated == stateStore.filters.value) return

        stateStore.updateFilters { updated }
        stateStore.persist()

        // Rebuild the tree to apply the filter
        project.getService(CircleCIToolWindowService::class.java).reloadTree()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Which runs to list: those on the current branch, the default branch, all
 * branches, or the user's own runs across every project.
 */
class RunScopeFilterAction : RunFilterComboAction() {
    override fun createPopupActionGroup(
        button: JComponent,
        dataContext: DataContext,
    ): DefaultActionGroup {
        return DefaultActionGroup().apply {
            add(SetRunScopeAction(RunScope.CURRENT_BRANCH))
            add(SetRunScopeAction(RunScope.DEFAULT_BRANCH))
            add(SetRunScopeAction(RunScope.ALL_BRANCHES))
            addSeparator()
            add(SetRunScopeAction(RunScope.MY_RUNS))
        }
    }

    override fun text(
        project: Project,
        filters: FiltersState,
    ): String {
        val gitService = GitBranchService.getInstance(project)
        return when (filters.scope) {
            RunScope.CURRENT_BRANCH -> "Branch: ${currentBranch(project, gitService) ?: "current"}"
            RunScope.DEFAULT_BRANCH -> "Branch: ${gitService.getDefaultBranch()}"
            RunScope.ALL_BRANCHES -> "Branch: all"
            RunScope.MY_RUNS -> "My runs"
        }
    }
}

/**
 * The current branch to name in the toolbar: the selected projects' branch
 * when they agree, otherwise null (each project lists its own repo's branch).
 */
private fun currentBranch(
    project: Project,
    gitService: GitBranchService,
): String? {
    val projects = project.getService(CircleCIProjectService::class.java).getSelectedProjectObjects()
    if (projects.isEmpty()) return gitService.getCurrentBranch()
    return projects.map { gitService.getCurrentBranch(it.localPath) }.distinct().singleOrNull()
}

private class SetRunScopeAction(private val scope: RunScope) : SetRunFilterAction(scope.label) {
    override fun isSelected(filters: FiltersState): Boolean = filters.scope == scope

    override fun apply(filters: FiltersState): FiltersState = filters.copy(scope = scope)
}

/**
 * Narrow the run list to a single status.
 */
class RunStatusFilterAction : RunFilterComboAction() {
    override fun createPopupActionGroup(
        button: JComponent,
        dataContext: DataContext,
    ): DefaultActionGroup {
        return DefaultActionGroup().apply {
            add(SetRunStatusAction(null))
            addSeparator()
            RunStatusFilter.entries.forEach { add(SetRunStatusAction(it)) }
        }
    }

    override fun text(
        project: Project,
        filters: FiltersState,
    ): String = "Status: ${filters.status?.label?.lowercase() ?: "all"}"
}

private class SetRunStatusAction(private val status: RunStatusFilter?) :
    SetRunFilterAction(status?.label ?: "All Statuses") {
    override fun isSelected(filters: FiltersState): Boolean = filters.status == status

    override fun apply(filters: FiltersState): FiltersState = filters.copy(status = status)
}

/**
 * Narrow the run list to runs created more or less recently than a given age.
 */
class RunCreatedFilterAction : RunFilterComboAction() {
    override fun createPopupActionGroup(
        button: JComponent,
        dataContext: DataContext,
    ): DefaultActionGroup {
        return DefaultActionGroup().apply {
            add(SetRunCreatedAction(null))
            addSeparator()
            add(createdGroup("Newer Than", newer = true))
            add(createdGroup("Older Than", newer = false))
        }
    }

    private fun createdGroup(
        text: String,
        newer: Boolean,
    ): DefaultActionGroup {
        return DefaultActionGroup.createPopupGroup { text }.apply {
            CreatedAge.entries.forEach { add(SetRunCreatedAction(CreatedFilter(it, newer))) }
        }
    }

    override fun text(
        project: Project,
        filters: FiltersState,
    ): String = "Created: ${filters.created?.label?.lowercase() ?: "any time"}"
}

private class SetRunCreatedAction(private val created: CreatedFilter?) :
    SetRunFilterAction(created?.age?.label ?: "Any Time") {
    override fun isSelected(filters: FiltersState): Boolean = filters.created == created

    override fun apply(filters: FiltersState): FiltersState = filters.copy(created = created)
}
