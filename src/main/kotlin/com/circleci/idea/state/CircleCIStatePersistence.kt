package com.circleci.idea.state

import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.run.CreatedAge
import com.circleci.idea.run.CreatedFilter
import com.circleci.idea.run.RunScope
import com.circleci.idea.run.RunStatusFilter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Handles persistence of CircleCI state using PropertiesComponent.
 * Stores workspace-scoped preferences that should survive IDE restarts.
 */
@Service(Service.Level.PROJECT)
class CircleCIStatePersistence(private val project: Project) {
    private val logger = CircleCILogger.getInstance()
    private val properties: PropertiesComponent
        get() = PropertiesComponent.getInstance(project)

    private val gson = Gson()

    companion object {
        private const val KEY_FILTERS = "circleci.filters"
        private const val KEY_SELECTED_PROJECTS = "circleci.selectedProjects"
        private const val KEY_UI_STATE = "circleci.uiState"
        private const val KEY_RUN_SCOPE = "circleci.filters.runScope"
        private const val KEY_RUN_STATUS = "circleci.filters.runStatus"
        private const val KEY_RUN_CREATED_AGE = "circleci.filters.runCreatedAge"
        private const val KEY_RUN_CREATED_NEWER = "circleci.filters.runCreatedNewer"

        // Filters from before runs replaced pipelines; cleared on save.
        private val LEGACY_FILTER_KEYS =
            listOf("circleci.filters.branch", "circleci.filters.myPipelinesOnly", "circleci.filters.status")
        private const val KEY_EXPANDED_ITEMS = "circleci.ui.expandedItems"
        private const val KEY_NOTIFICATIONS_ENABLED = "circleci.ui.notifications.enabled"
        private const val KEY_NOTIFICATIONS_MY_RUNS = "circleci.ui.notifications.myPipelines"
        private const val KEY_NOTIFICATIONS_STATUS = "circleci.ui.notifications.status"
    }

    /**
     * Load filters from persistence. Unknown values (e.g. from a newer plugin
     * version) fall back to the defaults.
     */
    fun loadFilters(): FiltersState {
        val scope =
            properties.getValue(KEY_RUN_SCOPE)?.let { name -> RunScope.entries.find { it.name == name } }
                ?: RunScope.CURRENT_BRANCH
        val status =
            properties.getValue(KEY_RUN_STATUS)?.let {
                    name ->
                RunStatusFilter.entries.find { it.name == name }
            }
        val createdAge =
            properties.getValue(KEY_RUN_CREATED_AGE)?.let {
                    name ->
                CreatedAge.entries.find { it.name == name }
            }
        val created = createdAge?.let { CreatedFilter(it, newer = properties.getBoolean(KEY_RUN_CREATED_NEWER, true)) }

        return FiltersState(scope = scope, status = status, created = created)
    }

    /**
     * Save filters to persistence.
     */
    fun saveFilters(filters: FiltersState) {
        properties.setValue(KEY_RUN_SCOPE, filters.scope.name)
        properties.setValue(KEY_RUN_STATUS, filters.status?.name)
        properties.setValue(KEY_RUN_CREATED_AGE, filters.created?.age?.name)
        properties.setValue(KEY_RUN_CREATED_NEWER, filters.created?.newer ?: true, true)
        LEGACY_FILTER_KEYS.forEach { properties.unsetValue(it) }
    }

    /**
     * Load selected projects from persistence.
     */
    fun loadSelectedProjects(): List<String> {
        return try {
            val json = properties.getValue(KEY_SELECTED_PROJECTS, "[]")
            gson.fromJson(json, object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) {
            logger.warn("Failed to load selected projects from persistence", e)
            emptyList()
        }
    }

    /**
     * Save selected projects to persistence.
     */
    fun saveSelectedProjects(projects: List<String>) {
        properties.setValue(KEY_SELECTED_PROJECTS, gson.toJson(projects))
    }

    /**
     * Load UI state from persistence.
     */
    fun loadUIState(): UIState? {
        return try {
            val expandedItemsJson = properties.getValue(KEY_EXPANDED_ITEMS, "[]")
            val expandedItems =
                gson.fromJson<Set<String>>(
                    expandedItemsJson,
                    object : TypeToken<Set<String>>() {}.type,
                )

            val notificationsEnabled = properties.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
            val notificationsMyRuns = properties.getBoolean(KEY_NOTIFICATIONS_MY_RUNS, false)
            val notificationsStatusJson =
                properties.getValue(
                    KEY_NOTIFICATIONS_STATUS,
                    gson.toJson(setOf("failed", "failing", "canceled", "on_hold", "error", "unauthorized")),
                )
            val notificationsStatus =
                gson.fromJson<Set<String>>(
                    notificationsStatusJson,
                    object : TypeToken<Set<String>>() {}.type,
                )

            UIState(
                expandedItems = expandedItems,
                notificationPreferences =
                    NotificationPreferences(
                        enabled = notificationsEnabled,
                        myRunsOnly = notificationsMyRuns,
                        statusFilter = notificationsStatus,
                    ),
            )
        } catch (e: Exception) {
            logger.warn("Failed to load UI state from persistence", e)
            null
        }
    }

    /**
     * Save UI state to persistence.
     */
    fun saveUIState(uiState: UIState) {
        properties.setValue(KEY_EXPANDED_ITEMS, gson.toJson(uiState.expandedItems))
        properties.setValue(KEY_NOTIFICATIONS_ENABLED, uiState.notificationPreferences.enabled)
        properties.setValue(KEY_NOTIFICATIONS_MY_RUNS, uiState.notificationPreferences.myRunsOnly)
        properties.setValue(KEY_NOTIFICATIONS_STATUS, gson.toJson(uiState.notificationPreferences.statusFilter))
    }

    /**
     * Clear all persisted state.
     */
    fun clearAll() {
        properties.unsetValue(KEY_FILTERS)
        properties.unsetValue(KEY_SELECTED_PROJECTS)
        properties.unsetValue(KEY_UI_STATE)
        properties.unsetValue(KEY_RUN_SCOPE)
        properties.unsetValue(KEY_RUN_STATUS)
        properties.unsetValue(KEY_RUN_CREATED_AGE)
        properties.unsetValue(KEY_RUN_CREATED_NEWER)
        LEGACY_FILTER_KEYS.forEach { properties.unsetValue(it) }
        properties.unsetValue(KEY_EXPANDED_ITEMS)
        properties.unsetValue(KEY_NOTIFICATIONS_ENABLED)
        properties.unsetValue(KEY_NOTIFICATIONS_MY_RUNS)
        properties.unsetValue(KEY_NOTIFICATIONS_STATUS)
    }
}
