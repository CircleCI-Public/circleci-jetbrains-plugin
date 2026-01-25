package com.circleci.idea.toolwindow.actions

import com.circleci.idea.git.GitBranchService
import com.circleci.idea.state.BranchFilter
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.toolwindow.CircleCIToolWindowService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import javax.swing.JComponent

/**
 * Branch filter combo box action.
 */
class BranchFilterAction : ComboBoxAction(), DumbAware {

    override fun createPopupActionGroup(button: JComponent?): DefaultActionGroup {
        return DefaultActionGroup().apply {
            add(SetBranchFilterAction("Current Branch", BranchFilter.CURRENT))
            add(SetBranchFilterAction("All Branches", BranchFilter.ALL))
            add(SetBranchFilterAction("Default Branch", BranchFilter.DEFAULT))
            addSeparator()
            add(SetCustomBranchFilterAction())
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }

        val stateStore = CircleCIStateStore.getInstance(project)
        val filters = stateStore.filters.value
        val gitService = GitBranchService.getInstance(project)

        val text = when (filters.branchFilter) {
            BranchFilter.CURRENT -> {
                val currentBranch = gitService.getCurrentBranch()
                if (currentBranch != null) "Branch: $currentBranch" else "Branch: Current"
            }
            BranchFilter.ALL -> "Branch: All"
            BranchFilter.DEFAULT -> {
                val defaultBranch = gitService.getDefaultBranch()
                "Branch: $defaultBranch"
            }
            BranchFilter.CUSTOM -> "Branch: Custom"
        }

        e.presentation.text = text
        e.presentation.isEnabled = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}

/**
 * Action to set a specific branch filter.
 */
class SetBranchFilterAction(
    private val displayName: String,
    private val filter: BranchFilter
) : AnAction(displayName), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stateStore = CircleCIStateStore.getInstance(project)

        stateStore.updateFilters { it.copy(branchFilter = filter) }
        stateStore.persist()

        // Refresh tree to apply filter
        project.getService(CircleCIToolWindowService::class.java).reloadTree()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }

        val stateStore = CircleCIStateStore.getInstance(project)
        val currentFilter = stateStore.filters.value.branchFilter

        // Show checkmark if this filter is selected
        e.presentation.icon = if (currentFilter == filter) {
            AllIcons.Actions.Checked
        } else {
            null
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}

/**
 * Action to set a custom branch filter.
 */
class SetCustomBranchFilterAction : AnAction("Custom Branch..."), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val branchName = Messages.showInputDialog(
            project,
            "Enter branch name:",
            "Filter by Branch",
            Messages.getQuestionIcon()
        )

        if (branchName.isNullOrBlank()) {
            return
        }

        val stateStore = CircleCIStateStore.getInstance(project)
        stateStore.updateFilters {
            it.copy(
                branchFilter = BranchFilter.CUSTOM,
                authorFilter = branchName // Reusing authorFilter for custom branch name
            )
        }
        stateStore.persist()

        // Refresh tree to apply filter
        project.getService(CircleCIToolWindowService::class.java).reloadTree()
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}

/**
 * Toggle action for "My Pipelines Only" filter.
 */
class MyPipelinesOnlyAction : ToggleAction(
    "My Pipelines Only",
    "Show only pipelines triggered by you",
    AllIcons.General.User
), DumbAware {

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        val stateStore = CircleCIStateStore.getInstance(project)
        return stateStore.filters.value.myPipelinesOnly
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        val stateStore = CircleCIStateStore.getInstance(project)

        stateStore.updateFilters { it.copy(myPipelinesOnly = state) }
        stateStore.persist()

        // Refresh tree to apply filter
        project.getService(CircleCIToolWindowService::class.java).reloadTree()
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}

/**
 * Status filter action.
 */
class StatusFilterAction : ComboBoxAction(), DumbAware {

    override fun createPopupActionGroup(button: JComponent?): DefaultActionGroup {
        return DefaultActionGroup().apply {
            add(ToggleStatusFilterAction("Success", "success"))
            add(ToggleStatusFilterAction("Failed", "failed"))
            add(ToggleStatusFilterAction("Failing", "failing"))
            add(ToggleStatusFilterAction("Running", "running"))
            add(ToggleStatusFilterAction("On Hold", "on_hold"))
            add(ToggleStatusFilterAction("Canceled", "canceled"))
            add(ToggleStatusFilterAction("Error", "error"))
            addSeparator()
            add(ClearStatusFilterAction())
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }

        val stateStore = CircleCIStateStore.getInstance(project)
        val statusFilter = stateStore.filters.value.statusFilter

        val text = if (statusFilter.isEmpty()) {
            "Status: All"
        } else if (statusFilter.size == 1) {
            "Status: ${statusFilter.first().capitalize()}"
        } else {
            "Status: ${statusFilter.size} selected"
        }

        e.presentation.text = text
        e.presentation.isEnabled = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}

/**
 * Toggle action for specific status filter.
 */
class ToggleStatusFilterAction(
    private val displayName: String,
    private val status: String
) : ToggleAction(displayName), DumbAware {

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        val stateStore = CircleCIStateStore.getInstance(project)
        return stateStore.filters.value.statusFilter.contains(status)
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        val stateStore = CircleCIStateStore.getInstance(project)

        stateStore.updateFilters { filters ->
            val newStatusFilter = if (state) {
                filters.statusFilter + status
            } else {
                filters.statusFilter - status
            }
            filters.copy(statusFilter = newStatusFilter)
        }
        stateStore.persist()

        // Refresh tree to apply filter
        project.getService(CircleCIToolWindowService::class.java).reloadTree()
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}

/**
 * Action to clear all status filters.
 */
class ClearStatusFilterAction : AnAction("Clear Filters"), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stateStore = CircleCIStateStore.getInstance(project)

        stateStore.updateFilters { it.copy(statusFilter = emptySet()) }
        stateStore.persist()

        // Refresh tree to apply filter
        project.getService(CircleCIToolWindowService::class.java).reloadTree()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project ?: return
        val stateStore = CircleCIStateStore.getInstance(project)
        e.presentation.isEnabled = stateStore.filters.value.statusFilter.isNotEmpty()
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}

/**
 * Helper function to capitalize first letter.
 */
private fun String.capitalize(): String {
    return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
