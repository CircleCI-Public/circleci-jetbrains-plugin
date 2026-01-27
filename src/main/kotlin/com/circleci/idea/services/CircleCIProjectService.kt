package com.circleci.idea.services

import com.circleci.idea.state.CircleCIStateStore
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Project-level service for managing CircleCI operations.
 * Coordinates between the state store, API client, and UI components.
 */
@Service(Service.Level.PROJECT)
class CircleCIProjectService(private val project: Project) {
    // Coroutine scope for async operations
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Access to state store
    private val stateStore: CircleCIStateStore by lazy {
        project.service<CircleCIStateStore>()
    }

    init {
        // Initialize project-level services
        // State store will auto-hydrate from persistence

        // Try to restore authentication if token exists
        val authService = project.service<com.circleci.idea.auth.CircleCIAuthService>()
        authService.restoreAuthentication()
    }

    /**
     * Refresh pipelines for all selected projects.
     */
    fun refreshPipelines() {
        // TODO: Implement pipeline refresh logic using API client
        // This will be implemented after API client is ready
    }

    /**
     * Cleanup resources when project is closed.
     */
    fun disconnect() {
        // Persist current state before disconnecting
        stateStore.persist()

        // Cancel all ongoing coroutines
        serviceScope.cancel()
    }

    companion object {
        fun getInstance(project: Project): CircleCIProjectService {
            return project.service()
        }
    }
}
