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

    private val _jobDetails = MutableStateFlow(JobDetailsState())
    override val jobDetails: StateFlow<JobDetailsState> = _jobDetails.asStateFlow()

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
     * Update job details state.
     */
    fun updateJobDetails(update: (JobDetailsState) -> JobDetailsState) {
        _jobDetails.value = update(_jobDetails.value)
    }

    /**
     * Select a job and prepare to load its details.
     */
    fun selectJob(
        jobId: String,
        jobNumber: Long?,
        projectSlug: String,
    ) {
        _jobDetails.value =
            JobDetailsState(
                selectedJobId = jobId,
                selectedJobNumber = jobNumber,
                selectedProjectSlug = projectSlug,
                isLoading = true,
            )
    }

    /**
     * Set job details after fetching.
     */
    fun setJobDetails(jobDetails: JobDetails) {
        updateJobDetails { state ->
            state.copy(
                jobDetails = jobDetails,
                isLoading = false,
                error = null,
            )
        }
    }

    /**
     * Set job details error.
     */
    fun setJobDetailsError(error: String) {
        updateJobDetails { state ->
            state.copy(
                isLoading = false,
                error = error,
            )
        }
    }

    /**
     * Clear selected job.
     */
    fun clearJobDetails() {
        _jobDetails.value = JobDetailsState()
    }

    /**
     * Add or update pipeline data for a specific project.
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
     * Add pipelines to a project's data.
     */
    fun addPipelines(
        projectSlug: String,
        pipelines: List<Pipeline>,
        nextPageToken: String? = null,
    ) {
        updateProjectData(projectSlug) { currentData ->
            val existing = currentData ?: ProjectData(projectSlug)
            existing.copy(
                pipelines = existing.pipelines + pipelines,
                nextPageToken = nextPageToken,
                isLoading = false,
            )
        }
    }

    /**
     * Replace all pipelines for a project (e.g., after refresh).
     */
    fun setPipelines(
        projectSlug: String,
        pipelines: List<Pipeline>,
        nextPageToken: String? = null,
    ) {
        updateProjectData(projectSlug) { currentData ->
            val existing = currentData ?: ProjectData(projectSlug)
            existing.copy(
                pipelines = pipelines,
                nextPageToken = nextPageToken,
                isLoading = false,
            )
        }
    }

    /**
     * Update workflows for a specific pipeline.
     */
    fun updateWorkflows(
        projectSlug: String,
        pipelineId: String,
        workflows: List<Workflow>,
    ) {
        updateProjectData(projectSlug) { currentData ->
            val existing = currentData ?: ProjectData(projectSlug)
            val updatedPipelines =
                existing.pipelines.map { pipeline ->
                    if (pipeline.id == pipelineId) {
                        pipeline.copy(workflows = workflows)
                    } else {
                        pipeline
                    }
                }
            existing.copy(pipelines = updatedPipelines)
        }
    }

    /**
     * Update jobs for a specific workflow.
     */
    fun updateJobs(
        projectSlug: String,
        workflowId: String,
        jobs: List<Job>,
    ) {
        updateProjectData(projectSlug) { currentData ->
            val existing = currentData ?: ProjectData(projectSlug)
            val updatedPipelines =
                existing.pipelines.map { pipeline ->
                    val updatedWorkflows =
                        pipeline.workflows.map { workflow ->
                            if (workflow.id == workflowId) {
                                workflow.copy(jobs = jobs)
                            } else {
                                workflow
                            }
                        }
                    pipeline.copy(workflows = updatedWorkflows)
                }
            existing.copy(pipelines = updatedPipelines)
        }
    }

    /**
     * Update notification preferences.
     */
    fun updateNotificationPreferences(
        enabled: Boolean? = null,
        myPipelinesOnly: Boolean? = null,
        statusFilter: Set<String>? = null,
    ) {
        _ui.value =
            _ui.value.copy(
                notificationPreferences =
                    _ui.value.notificationPreferences.copy(
                        enabled = enabled ?: _ui.value.notificationPreferences.enabled,
                        myPipelinesOnly = myPipelinesOnly ?: _ui.value.notificationPreferences.myPipelinesOnly,
                        statusFilter = statusFilter ?: _ui.value.notificationPreferences.statusFilter,
                    ),
            )
        persist()
    }

    /**
     * Clear all pipeline data (e.g., on logout).
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
        val persistedFilters = persistence.loadFilters()
        if (persistedFilters != null) {
            _filters.value = persistedFilters
        }

        // Load selected projects
        val persistedProjects = persistence.loadSelectedProjects()
        if (persistedProjects.isNotEmpty()) {
            updateProjects { it.copy(selectedProjects = persistedProjects) }
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
        persistence.saveSelectedProjects(_projects.value.selectedProjects)
        persistence.saveUIState(_ui.value)
    }

    companion object {
        fun getInstance(project: Project): CircleCIStateStore {
            return project.service()
        }
    }
}
