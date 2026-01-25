package com.circleci.idea.project

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.project.models.CircleCIProject
import com.circleci.idea.state.CircleCIStateStore
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Service for managing CircleCI projects in the workspace.
 * Handles auto-detection, manual selection, and persistence.
 */
@Service(Service.Level.PROJECT)
class CircleCIProjectService(private val project: Project) {

    private val logger = CircleCILogger.getInstance()
    private val scanner = GitRepositoryScanner(project)
    private val stateStore = project.getService(CircleCIStateStore::class.java)

    private val _projects = MutableStateFlow<List<CircleCIProject>>(emptyList())
    val projects: StateFlow<List<CircleCIProject>> = _projects.asStateFlow()

    private val _selectedProjects = MutableStateFlow<Set<String>>(emptySet())
    val selectedProjects: StateFlow<Set<String>> = _selectedProjects.asStateFlow()

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
            _projects.value = detectedProjects

            logger.info("Auto-detected ${detectedProjects.size} projects")

            // Auto-select detected projects if none are selected
            if (_selectedProjects.value.isEmpty() && detectedProjects.isNotEmpty()) {
                val slugs = detectedProjects.map { it.slug }.toSet()
                selectProjects(slugs)
                logger.info("Auto-selected ${slugs.size} detected projects")
            }
        } catch (e: Exception) {
            logger.error("Failed to detect projects", e)
        } finally {
            _isLoading.value = false
        }
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
     * Merge followed projects with detected projects.
     */
    private fun mergeWithDetectedProjects(followedProjects: List<CircleCIProject>) {
        val detectedProjects = _projects.value.toMutableList()
        val detectedSlugs = detectedProjects.map { it.slug }.toSet()

        // Add followed projects that weren't detected locally
        for (followedProject in followedProjects) {
            if (followedProject.slug !in detectedSlugs) {
                detectedProjects.add(followedProject.copy(followed = true))
            } else {
                // Update followed status for detected projects
                val index = detectedProjects.indexOfFirst { it.slug == followedProject.slug }
                if (index >= 0) {
                    detectedProjects[index] = detectedProjects[index].copy(
                        followed = true,
                        defaultBranch = followedProject.defaultBranch ?: detectedProjects[index].defaultBranch
                    )
                }
            }
        }

        _projects.value = detectedProjects
        logger.debug("Merged projects: ${detectedProjects.size} total")
    }

    /**
     * Select projects to monitor.
     */
    fun selectProjects(projectSlugs: Set<String>) {
        logger.info("Selecting ${projectSlugs.size} projects: ${projectSlugs.joinToString()}")
        _selectedProjects.value = projectSlugs
        persistSelection(projectSlugs)

        logger.logStateChange("selectedProjects", _selectedProjects.value.size, projectSlugs.size)
    }

    /**
     * Add a project by slug.
     */
    fun addProjectBySlug(slug: String): Boolean {
        logger.info("Adding project by slug: $slug")

        val project = CircleCIProject.fromSlug(slug)
        if (project == null) {
            logger.warn("Invalid project slug: $slug")
            return false
        }

        // Check if already exists
        val existingSlugs = _projects.value.map { it.slug }
        if (slug in existingSlugs) {
            logger.debug("Project already exists: $slug")
        } else {
            // Add to projects list
            _projects.value = _projects.value + project
            logger.info("Added project: $slug")
        }

        // Add to selection
        val newSelection = _selectedProjects.value + slug
        selectProjects(newSelection)

        return true
    }

    /**
     * Remove a project from selection.
     */
    fun removeProject(slug: String) {
        logger.info("Removing project: $slug")
        val newSelection = _selectedProjects.value - slug
        selectProjects(newSelection)
    }

    /**
     * Get all available projects (detected + followed).
     */
    fun getAllProjects(): List<CircleCIProject> {
        return _projects.value
    }

    /**
     * Get currently selected projects.
     */
    fun getSelectedProjectObjects(): List<CircleCIProject> {
        val selectedSlugs = _selectedProjects.value
        return _projects.value.filter { it.slug in selectedSlugs }
    }

    /**
     * Check if a project is selected.
     */
    fun isProjectSelected(slug: String): Boolean {
        return slug in _selectedProjects.value
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
     * Persist selected projects to state.
     */
    private fun persistSelection(projectSlugs: Set<String>) {
        try {
            stateStore.updateProjects { it.copy(selectedProjects = projectSlugs.toList()) }
            stateStore.persist()
            logger.debug("Persisted ${projectSlugs.size} selected projects")
        } catch (e: Exception) {
            logger.error("Failed to persist project selection", e)
        }
    }

    /**
     * Load persisted project selection.
     */
    private fun loadPersistedSelection() {
        try {
            val persisted = stateStore.projects.value.selectedProjects.toSet()
            if (persisted.isNotEmpty()) {
                _selectedProjects.value = persisted
                logger.info("Loaded ${persisted.size} persisted projects")
            }
        } catch (e: Exception) {
            logger.error("Failed to load persisted project selection", e)
        }
    }
}
