package com.circleci.idea.project

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.project.models.CircleCIProject
import com.circleci.idea.state.CircleCIStateStore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

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

    // The projects added by slug, rather than found in the workspace.
    private val manualSlugs = mutableSetOf<String>()

    // The projects .circleci/info.yml files linked to at the last scan, and whether there's been one.
    private var linkedSlugs = emptySet<String>()
    private var hasScanned = false

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
                    existing.slug in manualSlugs && detectedProjects.none { detected -> detected.slug == existing.slug }
                }

            // Merge detected and manual projects
            _projects.value = detectedProjects + existingManualProjects

            logger.info(
                "Auto-detected ${detectedProjects.size} projects " +
                    "(${existingManualProjects.size} manual projects preserved)",
            )

            // Select the first detected project if none is, or the selected one is gone. A
            // link that's new since the last scan, e.g. from `circleci project link`, wins.
            val linked = detectedProjects.filter { it.linked }.map { it.slug }
            val newLink = linked.firstOrNull { it !in linkedSlugs }
            linkedSlugs = linked.toSet()
            if (newLink != null && hasScanned) {
                selectProject(newLink)
                logger.info("Selected newly linked project $newLink")
            } else if (getSelectedProject() == null) {
                detectedProjects.firstOrNull()?.let {
                    selectProject(it.slug)
                    logger.info("Auto-selected detected project ${it.slug}")
                }
            }
            if (detectedProjects.isNotEmpty()) hasScanned = true
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

        manualSlugs += slug
        if (_projects.value.none { it.slug == slug }) {
            _projects.value = _projects.value + project
            logger.info("Added project: $slug")
        }
        selectProject(slug)
        return true
    }

    /**
     * Link the workspace to the project [slug] names, as `circleci project link`
     * does: record it in `.circleci/info.yml`, where the CLI and detection here
     * look first. The file goes in the checkout the project was found in, else
     * the project's directory. Gives the file written, or null if it already
     * linked to the project. Fails if there's no such project, or nowhere to write.
     */
    suspend fun linkProject(slug: String): Result<Path?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root =
                    (_projects.value.firstOrNull { it.slug == slug }?.localPath ?: project.basePath)
                        ?.let { Path.of(it) }
                        ?: error("there's no project directory to link")
                val existing = runCatching { ProjectLinkFile.read(root) }.getOrNull()
                if (existing?.effectiveSlug == slug && existing.projectId != null) return@runCatching null

                val info = CircleCIApiService.getInstance().getProjectLink(slug).getOrThrow()
                ProjectLinkFile.write(root, info)
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(ProjectLinkFile.path(root))
                logger.info("Linked ${ProjectLinkFile.path(root)} to $slug")
                detectProjects()
                ProjectLinkFile.path(root)
            }
        }

    /** [linkProject] without waiting for it, saying where it linked or why it couldn't. */
    fun linkProjectInBackground(slug: String) {
        coroutineScope.launch {
            linkProject(slug).fold(
                onSuccess = { file ->
                    file?.let { notify("Linked $it → $slug", NotificationType.INFORMATION) }
                },
                onFailure = {
                    logger.warn("Failed to link $slug: ${it.message}")
                    notify("Couldn't link ${ProjectLinkFile.PATH} to $slug: ${it.message}", NotificationType.WARNING)
                },
            )
        }
    }

    private fun notify(
        message: String,
        type: NotificationType,
    ) {
        NotificationGroupManager.getInstance().getNotificationGroup("CircleCI Notifications")
            .createNotification(message, type)
            .notify(project)
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
