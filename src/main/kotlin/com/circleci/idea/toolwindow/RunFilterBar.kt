package com.circleci.idea.toolwindow

import com.circleci.idea.git.GitBranchService
import com.circleci.idea.project.CircleCIProjectService
import com.circleci.idea.project.models.CircleCIProject
import com.circleci.idea.run.CreatedAge
import com.circleci.idea.run.CreatedFilter
import com.circleci.idea.run.RunScope
import com.circleci.idea.run.RunStatusFilter
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.state.FiltersState
import com.intellij.collaboration.ui.codereview.list.search.DropDownComponentFactory
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.InplaceButton
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.FlowLayout
import kotlin.coroutines.resume

/**
 * The run list's filters as the IDE's Pull Requests list shows its own: a
 * row of "Name ▾" drop-downs, each of which reads just its name while it's
 * at its default and can be cleared back to it. A funnel before them clears
 * them all.
 *
 * Each filter's default is "unset" (null): the current branch, all
 * statuses, any time, and the workspace's first project.
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

    private val projectState = MutableStateFlow(projectService.getSelectedProject())
    private val branchState = MutableStateFlow(filters.scope.takeIf { it != RunScope.CURRENT_BRANCH })
    private val statusState = MutableStateFlow(filters.status)
    private val createdState = MutableStateFlow(filters.created)

    private val projectChip =
        DropDownComponentFactory(projectState).create(scope, "Project", {
            it.getDisplayName()
        }) { point -> chooseProject(point) }

    init {
        add(InplaceButton("Clear filters", AllIcons.General.Filter) { clearFilters() })
        add(projectChip)
        add(
            DropDownComponentFactory(branchState).create(
                scope,
                "Branch",
                RunScope.entries,
                {},
                ::branchLabel,
            ),
        )
        add(DropDownComponentFactory(statusState).create(scope, "Status", RunStatusFilter.entries, {}, { it.label }))
        add(DropDownComponentFactory(createdState).create(scope, "Created", CREATED_CHOICES, {}, { it.label }))

        projectChip.isVisible = filters.scope != RunScope.MY_RUNS
        followFilters()
    }

    /** Apply each drop-down's choice, and keep the project one showing the selected project. */
    private fun followFilters() {
        scope.launch {
            branchState.collect { choice ->
                // Choosing the current branch is the same as clearing the filter.
                if (choice == RunScope.CURRENT_BRANCH) {
                    branchState.value = null
                    return@collect
                }
                val scope = choice ?: RunScope.CURRENT_BRANCH
                projectChip.isVisible = scope != RunScope.MY_RUNS
                update { it.copy(scope = scope) }
            }
        }
        scope.launch { statusState.collect { status -> update { it.copy(status = status) } } }
        scope.launch { createdState.collect { created -> update { it.copy(created = created) } } }
        scope.launch {
            projectState.collect { chosen ->
                val projects = projectService.getAllProjects()
                // Before any projects are found there's nothing to choose (and
                // the remembered selection mustn't be cleared).
                if (projects.isEmpty()) return@collect
                // Cleared: back to the workspace's first project.
                projectService.selectProject(chosen?.slug ?: projects.first().slug)
            }
        }
        scope.launch {
            projectService.selectedProject.collect { projectState.value = projectService.getSelectedProject() }
        }
        scope.launch {
            projectService.projects.collect { projectState.value = projectService.getSelectedProject() }
        }
    }

    private fun update(change: (FiltersState) -> FiltersState) {
        val updated = change(filters)
        if (updated == filters) return
        stateStore.updateFilters { updated }
        stateStore.persist()
        onFiltersChanged()
    }

    private fun clearFilters() {
        branchState.value = null
        statusState.value = null
        createdState.value = null
    }

    private fun branchLabel(scope: RunScope): String {
        val selected = projectService.getSelectedProject()
        return when (scope) {
            RunScope.CURRENT_BRANCH -> gitService.getCurrentBranch(selected?.localPath) ?: "Current branch"
            RunScope.DEFAULT_BRANCH -> selected?.defaultBranch ?: gitService.getDefaultBranch(selected?.localPath)
            RunScope.ALL_BRANCHES -> "All branches"
            RunScope.MY_RUNS -> "My runs"
        }
    }

    /**
     * Pick a project: one found in the workspace, or another by its slug.
     * Null when the popup is dismissed.
     */
    private suspend fun chooseProject(point: RelativePoint): CircleCIProject? {
        val choices: List<CircleCIProject?> = projectService.getAllProjects() + null
        val chosen =
            suspendCancellableCoroutine<Choice?> { continuation ->
                val step =
                    object : BaseListPopupStep<CircleCIProject?>(null, choices) {
                        override fun getTextFor(value: CircleCIProject?): String =
                            value?.getDisplayName() ?: OTHER_PROJECT

                        override fun isSelectable(value: CircleCIProject?): Boolean = true

                        override fun onChosen(
                            selectedValue: CircleCIProject?,
                            finalChoice: Boolean,
                        ): PopupStep<*>? =
                            doFinalStep {
                                if (continuation.isActive) continuation.resume(Choice(selectedValue))
                            }

                        override fun canceled() {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                val popup = JBPopupFactory.getInstance().createListPopup(step)
                continuation.invokeOnCancellation { popup.cancel() }
                popup.show(point)
            } ?: return null
        return chosen.project ?: promptForProject()
    }

    /** Ask for a project's slug, and add it. */
    private fun promptForProject(): CircleCIProject? {
        val slug =
            Messages.showInputDialog(
                project,
                "The CircleCI project's slug, as vcs/org/repo (e.g., gh/myorg/myrepo):",
                "Other CircleCI Project",
                null,
            )?.trim()
        if (slug.isNullOrEmpty()) return null
        if (!projectService.addProjectBySlug(slug)) {
            Messages.showErrorDialog(
                project,
                "\"$slug\" isn't a project slug: use vcs/org/repo, e.g. gh/myorg/myrepo",
                "Invalid Project Slug",
            )
            return null
        }
        return projectService.getSelectedProject()
    }

    /** A pick from the project popup; a null project is "Other Project...". */
    private class Choice(val project: CircleCIProject?)

    private companion object {
        const val GAP = 4
        const val OTHER_PROJECT = "Other Project..."

        val CREATED_CHOICES: List<CreatedFilter> =
            listOf(true, false).flatMap { newer -> CreatedAge.entries.map { CreatedFilter(it, newer) } }
    }
}
