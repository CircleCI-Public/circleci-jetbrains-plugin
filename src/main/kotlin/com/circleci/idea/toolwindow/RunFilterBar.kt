package com.circleci.idea.toolwindow

import com.circleci.idea.git.GitBranchService
import com.circleci.idea.project.CircleCIProjectService
import com.circleci.idea.run.CreatedAge
import com.circleci.idea.run.CreatedFilter
import com.circleci.idea.run.RunScope
import com.circleci.idea.run.RunStatusFilter
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.state.FiltersState
import com.intellij.collaboration.ui.codereview.list.search.DropDownComponentFactory
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.awt.FlowLayout

/**
 * The run list's filters as the IDE's Pull Requests list shows its own: a
 * row of drop-downs, and a funnel before them that resets them.
 *
 * Branch always has a value ("Branch: Current [main] ▾"), so has nothing to
 * clear. Status and Created are optional: each reads just its name while
 * unset, and can be cleared back to it. The project they apply to isn't a
 * filter; it's chosen in the tool window's title bar.
 */
class RunFilterBar(
    private val project: Project,
    private val scope: CoroutineScope,
    private val onFiltersChanged: () -> Unit,
) : JBPanel<RunFilterBar>(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(GAP))) {
    private val stateStore = CircleCIStateStore.getInstance(project)
    private val projectService = project.getService(CircleCIProjectService::class.java)
    private val gitService = GitBranchService.getInstance(project)

    private val filters: FiltersState
        get() = stateStore.filters.value

    private val branchState = MutableStateFlow(filters.scope)
    private val statusState = MutableStateFlow(filters.status)
    private val createdState = MutableStateFlow(filters.created)

    private val branchChip =
        RequiredFilterChip(
            "Branch",
            branchState,
            scope,
            choices = { RunScope.entries },
            text = ChipText(chip = { branchLabel(it, short = true) }, popup = { branchLabel(it, short = false) }),
        ).component

    init {
        add(InplaceButton("Reset filters", AllIcons.General.Filter) { resetFilters() })
        add(branchChip)
        add(DropDownComponentFactory(statusState).create(scope, "Status", RunStatusFilter.entries, {}, { it.label }))
        add(DropDownComponentFactory(createdState).create(scope, "Created", CREATED_CHOICES, {}, { it.label }))

        followFilters()
    }

    /** Apply each drop-down's choice. */
    private fun followFilters() {
        scope.launch {
            branchState.collect { scope -> update { it.copy(scope = scope) } }
        }
        scope.launch { statusState.collect { status -> update { it.copy(status = status) } } }
        scope.launch { createdState.collect { created -> update { it.copy(created = created) } } }
        // The branch names in the labels follow the selected project.
        scope.launch {
            projectService.selectedProject.collect {
                branchChip.revalidate()
                branchChip.repaint()
            }
        }
    }

    private fun update(change: (FiltersState) -> FiltersState) {
        val updated = change(filters)
        if (updated == filters) return
        stateStore.updateFilters { updated }
        stateStore.persist()
        onFiltersChanged()
    }

    /** Back to the defaults: the current branch, every status, any time. */
    private fun resetFilters() {
        branchState.value = RunScope.CURRENT_BRANCH
        statusState.value = null
        createdState.value = null
    }

    /**
     * A branch scope's label: its role, with the branch it means, e.g.
     * "Current [main]" on the chip and "Current branch [main]" in the popup,
     * so that on the default branch the two still read apart.
     */
    private fun branchLabel(
        scope: RunScope,
        short: Boolean,
    ): String {
        val selected = projectService.getSelectedProject()
        val branch =
            when (scope) {
                RunScope.CURRENT_BRANCH -> gitService.getCurrentBranch(selected?.localPath)
                RunScope.DEFAULT_BRANCH -> selected?.defaultBranch ?: gitService.getDefaultBranch(selected?.localPath)
                else -> null
            }
        val role =
            when (scope) {
                RunScope.CURRENT_BRANCH -> if (short) "Current" else "Current branch"
                RunScope.DEFAULT_BRANCH -> if (short) "Default" else "Default branch"
                RunScope.ALL_BRANCHES -> "All branches"
                RunScope.MY_RUNS -> "My runs"
            }
        return branch?.let { "$role [$it]" } ?: role
    }

    private companion object {
        const val GAP = 4

        val CREATED_CHOICES: List<CreatedFilter> =
            listOf(true, false).flatMap { newer -> CreatedAge.entries.map { CreatedFilter(it, newer) } }
    }
}
