package com.circleci.idea.project

import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.project.models.CircleCIProject
import com.circleci.idea.state.CircleCIStateStore
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Service for managing CircleCI projects in the workspace.
 * Handles auto-detection, manual selection, and persistence.
 */
@Service(Service.Level.PROJECT)
class CircleCIProjectService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    private val logger = CircleCILogger.getInstance()
    private val scanner = GitRepositoryScanner(project)
    private val stateStore = project.getService(CircleCIStateStore::class.java)

    private val _projects = MutableStateFlow<List<CircleCIProject>>(emptyList())
    val projects: StateFlow<List<CircleCIProject>> = _projects.asStateFlow()

    // The project whose runs the tool window lists, by slug.
    private val _selectedProject = MutableStateFlow<String?>(null)
    val selectedProject: StateFlow<String?> = _selectedProject.asStateFlow()

    private val _followedProjects = MutableStateFlow<List<CircleCIProject>>(emptyList())
    val followedProjects: StateFlow<List<CircleCIProject>> = _followedProjects.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        logger.logLifecycleEvent("CircleCIProjectService initialized")
        loadPersistedSelection()
    }

    /**
     * Auto-detect projects in the workspace.
     */
    suspend fun detectProjects() {
        logger.info("Starting project auto-detection")
        _isLoading.value = true

        try {
            val detectedProjects = scanner.scanForProjects()

            // Preserve manually added projects that aren't in git scan
            val existingManualProjects =
                _projects.value.filter { existing ->
                    detectedProjects.none { detected -> detected.slug == existing.slug }
                }

            // Merge detected and manual projects
            _projects.value = detectedProjects + existingManualProjects

            logger.info(
                "Auto-detected ${detectedProjects.size} projects " +
                    "(${existingManualProjects.size} manual projects preserved)",
            )

            // Select the first detected project if none is, or the selected one is gone
            if (getSelectedProject() == null) {
                detectedProjects.firstOrNull()?.let {
                    selectProject(it.slug)
                    logger.info("Auto-selected detected project ${it.slug}")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to detect projects", e)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * [detectProjects] without waiting for it, e.g. from a message bus listener.
     */
    fun detectProjectsInBackground() {
        coroutineScope.launch { detectProjects() }
    }

    /**
     * Fetch followed projects from CircleCI API.
     * Note: This is currently disabled due to API format issues.
     * Projects are auto-detected from git remotes instead.
     */
    suspend fun fetchFollowedProjects() {
        logger.info("Fetching followed projects from CircleCI")
        _isLoading.value = true

        try {
            // Check if we have a valid token before making API calls
            val authService = com.circleci.idea.auth.CircleCIAuthService.getInstance(project)
            if (!authService.isAuthenticated()) {
                logger.debug("Cannot fetch followed projects: not authenticated")
                _isLoading.value = false
                return
            }

            // TODO: Fix API response parsing for followed projects
            // The v1.1 /projects endpoint returns a different format than expected
            // For now, rely on git-based auto-detection which is more reliable
            logger.debug("Followed projects API disabled - using git-based detection only")

            /*
            // Ensure API service is initialized
            val token = authService.getToken()
            if (token == null) {
                logger.warn("Cannot fetch followed projects: no token available")
                _isLoading.value = false
                return
            }

            val settings = com.circleci.idea.settings.CircleCISettings.getInstance()
            val apiService = CircleCIApiService.getInstance()
            apiService.initialize(token, settings.hostUrl)

            val result = apiService.getFollowedProjects()

            result.onSuccess { projectInfos ->
                val projects = ProjectConverter.fromApiModels(projectInfos)
                _followedProjects.value = projects
                logger.info("Fetched ${projects.size} followed projects")

                // Merge with detected projects
                mergeWithDetectedProjects(projects)
            }.onFailure { error ->
                logger.error("Failed to fetch followed projects: ${error.message}", error)
            }
             */
        } catch (e: Exception) {
            logger.debug("Failed to fetch followed projects: ${e.message}")
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Select the project whose runs to list.
     */
    fun selectProject(slug: String?) {
        if (slug == _selectedProject.value) return
        logger.logStateChange("selectedProject", _selectedProject.value, slug)
        _selectedProject.value = slug
        persistSelection(slug)
    }

    /**
     * Add a project by slug and select it.
     */
    fun addProjectBySlug(slug: String): Boolean {
        logger.info("Adding project by slug: $slug")

        val project = CircleCIProject.fromSlug(slug)
        if (project == null) {
            logger.warn("Invalid project slug: $slug")
            return false
        }

        if (_projects.value.none { it.slug == slug }) {
            _projects.value = _projects.value + project
            logger.info("Added project: $slug")
        }
        selectProject(slug)
        return true
    }

    /**
     * Get all available projects (detected + followed).
     */
    fun getAllProjects(): List<CircleCIProject> {
        return _projects.value
    }

    /**
     * The selected project, or null if it's not (or no longer) among the known projects.
     */
    fun getSelectedProject(): CircleCIProject? {
        val selected = _selectedProject.value ?: return null
        return _projects.value.firstOrNull { it.slug == selected }
    }

    /**
     * Refresh projects (re-scan and re-fetch).
     */
    suspend fun refresh() {
        logger.info("Refreshing projects")
        detectProjects()
        fetchFollowedProjects()
    }

    /**
     * Persist the selected project to state.
     */
    private fun persistSelection(slug: String?) {
        try {
            stateStore.updateProjects { it.copy(selectedProject = slug) }
            stateStore.persist()
        } catch (e: Exception) {
            logger.error("Failed to persist project selection", e)
        }
    }

    /**
     * Load the persisted project selection.
     */
    private fun loadPersistedSelection() {
        try {
            stateStore.projects.value.selectedProject?.let {
                _selectedProject.value = it
                logger.info("Loaded persisted project $it")
            }
        } catch (e: Exception) {
            logger.error("Failed to load persisted project selection", e)
        }
    }
}
