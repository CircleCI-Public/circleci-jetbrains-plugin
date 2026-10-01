package com.circleci.idea.state

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Centralized state store for CircleCI plugin.
 * Implements reactive state management using Kotlin StateFlow.
 *
 * This is a project-level service that manages all plugin state.
 */
@Service(Service.Level.PROJECT)
class CircleCIStateStore(private val project: Project) : CircleCIState {
    private val _auth = MutableStateFlow(AuthState())
    override val auth: StateFlow<AuthState> = _auth.asStateFlow()

    private val _projects = MutableStateFlow(ProjectsState())
    override val projects: StateFlow<ProjectsState> = _projects.asStateFlow()

    private val _projectsData = MutableStateFlow(ProjectsDataState())
    override val projectsData: StateFlow<ProjectsDataState> = _projectsData.asStateFlow()

    private val _config = MutableStateFlow(ConfigState())
    override val config: StateFlow<ConfigState> = _config.asStateFlow()

    private val _filters = MutableStateFlow(FiltersState())
    override val filters: StateFlow<FiltersState> = _filters.asStateFlow()

    private val _ui = MutableStateFlow(UIState())
    override val ui: StateFlow<UIState> = _ui.asStateFlow()

    init {
        // Load persisted state on initialization
        hydrate()
    }

    /**
     * Update authentication state.
     */
    fun updateAuth(update: (AuthState) -> AuthState) {
        _auth.value = update(_auth.value)
    }

    /**
     * Update projects state.
     */
    fun updateProjects(update: (ProjectsState) -> ProjectsState) {
        _projects.value = update(_projects.value)
    }

    /**
     * Update projects data state.
     */
    fun updateProjectsData(update: (ProjectsDataState) -> ProjectsDataState) {
        _projectsData.value = update(_projectsData.value)
    }

    /**
     * Update config state.
     */
    fun updateConfig(update: (ConfigState) -> ConfigState) {
        _config.value = update(_config.value)
    }

    /**
     * Update filters state.
     */
    fun updateFilters(update: (FiltersState) -> FiltersState) {
        _filters.value = update(_filters.value)
    }

    /**
     * Update UI state.
     */
    fun updateUI(update: (UIState) -> UIState) {
        _ui.value = update(_ui.value)
    }

    /**
     * Add or update run data for a specific project.
     */
    fun updateProjectData(
        projectSlug: String,
        update: (ProjectData?) -> ProjectData,
    ) {
        updateProjectsData { state ->
            val currentData = state.data[projectSlug]
            val newData = update(currentData)
            state.copy(
                data = state.data + (projectSlug to newData),
            )
        }
    }

    /**
     * Replace the runs listed for a project (e.g., after a refresh), keeping the
     * workflows already loaded for runs that are still listed.
     */
    fun setRuns(
        projectSlug: String,
        runs: List<Run>,
        nextCursor: String? = null,
    ) {
        updateProjectData(projectSlug) { currentData ->
            val existing = currentData ?: ProjectData(projectSlug)
            val loadedWorkflows = existing.runs.associate { it.id to it.workflows }
            existing.copy(
                runs = runs.map { run -> run.copy(workflows = loadedWorkflows[run.id] ?: run.workflows) },
                nextCursor = nextCursor,
                isLoading = false,
            )
        }
    }

    /**
     * Update workflows for a specific run.
     */
    fun updateWorkflows(
        projectSlug: String,
        runId: String,
        workflows: List<Workflow>,
    ) {
        updateProjectData(projectSlug) { currentData ->
            val existing = currentData ?: ProjectData(projectSlug)
            val updatedRuns =
                existing.runs.map { run ->
                    if (run.id == runId) {
                        run.copy(workflows = workflows)
                    } else {
                        run
                    }
                }
            existing.copy(runs = updatedRuns)
        }
    }

    /**
     * Update notification preferences.
     */
    fun updateNotificationPreferences(
        enabled: Boolean? = null,
        myRunsOnly: Boolean? = null,
        statusFilter: Set<String>? = null,
    ) {
        _ui.value =
            _ui.value.copy(
                notificationPreferences =
                    _ui.value.notificationPreferences.copy(
                        enabled = enabled ?: _ui.value.notificationPreferences.enabled,
                        myRunsOnly = myRunsOnly ?: _ui.value.notificationPreferences.myRunsOnly,
                        statusFilter = statusFilter ?: _ui.value.notificationPreferences.statusFilter,
                    ),
            )
        persist()
    }

    /**
     * Clear all run data (e.g., on logout).
     */
    fun clearAllData() {
        _projectsData.value = ProjectsDataState()
        _projects.value = ProjectsState()
        _config.value = ConfigState()
    }

    /**
     * Hydrate state from persistence layer.
     */
    private fun hydrate() {
        // Load state from PropertiesComponent
        val persistence = project.service<CircleCIStatePersistence>()

        // Load filters
        _filters.value = persistence.loadFilters()

        // Load selected projects
        val persistedProjects = persistence.loadSelectedProjects()
        if (persistedProjects.isNotEmpty()) {
            updateProjects { it.copy(selectedProject = persistedProjects.first()) }
        }

        // Load UI preferences
        val persistedUIState = persistence.loadUIState()
        if (persistedUIState != null) {
            _ui.value = persistedUIState
        }
    }

    /**
     * Persist current state.
     */
    fun persist() {
        val persistence = project.service<CircleCIStatePersistence>()

        persistence.saveFilters(_filters.value)
        persistence.saveSelectedProjects(listOfNotNull(_projects.value.selectedProject))
        persistence.saveUIState(_ui.value)
    }

    companion object {
        fun getInstance(project: Project): CircleCIStateStore {
            return project.service()
        }
    }
}
