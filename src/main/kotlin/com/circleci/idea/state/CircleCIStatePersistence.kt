package com.circleci.idea.state

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

    private val properties: PropertiesComponent
        get() = PropertiesComponent.getInstance(project)

    private val gson = Gson()

    companion object {
        private const val KEY_FILTERS = "circleci.filters"
        private const val KEY_SELECTED_PROJECTS = "circleci.selectedProjects"
        private const val KEY_UI_STATE = "circleci.uiState"
        private const val KEY_BRANCH_FILTER = "circleci.filters.branch"
        private const val KEY_MY_PIPELINES_ONLY = "circleci.filters.myPipelinesOnly"
        private const val KEY_STATUS_FILTER = "circleci.filters.status"
        private const val KEY_EXPANDED_ITEMS = "circleci.ui.expandedItems"
        private const val KEY_NOTIFICATIONS_ENABLED = "circleci.ui.notifications.enabled"
        private const val KEY_NOTIFICATIONS_MY_PIPELINES = "circleci.ui.notifications.myPipelines"
        private const val KEY_NOTIFICATIONS_STATUS = "circleci.ui.notifications.status"
    }

    /**
     * Load filters from persistence.
     */
    fun loadFilters(): FiltersState? {
        return try {
            val branchFilterName = properties.getValue(KEY_BRANCH_FILTER, BranchFilter.CURRENT.name)
            val branchFilter = BranchFilter.valueOf(branchFilterName)
            val myPipelinesOnly = properties.getBoolean(KEY_MY_PIPELINES_ONLY, false)
            val statusFilterJson = properties.getValue(KEY_STATUS_FILTER, "[]")
            val statusFilter = gson.fromJson<Set<String>>(
                statusFilterJson,
                object : TypeToken<Set<String>>() {}.type
            )

            FiltersState(
                branchFilter = branchFilter,
                myPipelinesOnly = myPipelinesOnly,
                statusFilter = statusFilter
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Save filters to persistence.
     */
    fun saveFilters(filters: FiltersState) {
        properties.setValue(KEY_BRANCH_FILTER, filters.branchFilter.name)
        properties.setValue(KEY_MY_PIPELINES_ONLY, filters.myPipelinesOnly)
        properties.setValue(KEY_STATUS_FILTER, gson.toJson(filters.statusFilter))
    }

    /**
     * Load selected projects from persistence.
     */
    fun loadSelectedProjects(): List<String> {
        return try {
            val json = properties.getValue(KEY_SELECTED_PROJECTS, "[]")
            gson.fromJson(json, object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) {
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
            val expandedItems = gson.fromJson<Set<String>>(
                expandedItemsJson,
                object : TypeToken<Set<String>>() {}.type
            )

            val notificationsEnabled = properties.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
            val notificationsMyPipelines = properties.getBoolean(KEY_NOTIFICATIONS_MY_PIPELINES, false)
            val notificationsStatusJson = properties.getValue(
                KEY_NOTIFICATIONS_STATUS,
                gson.toJson(setOf("failed", "failing", "canceled", "on_hold", "error", "unauthorized"))
            )
            val notificationsStatus = gson.fromJson<Set<String>>(
                notificationsStatusJson,
                object : TypeToken<Set<String>>() {}.type
            )

            UIState(
                expandedItems = expandedItems,
                notificationPreferences = NotificationPreferences(
                    enabled = notificationsEnabled,
                    myPipelinesOnly = notificationsMyPipelines,
                    statusFilter = notificationsStatus
                )
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Save UI state to persistence.
     */
    fun saveUIState(uiState: UIState) {
        properties.setValue(KEY_EXPANDED_ITEMS, gson.toJson(uiState.expandedItems))
        properties.setValue(KEY_NOTIFICATIONS_ENABLED, uiState.notificationPreferences.enabled)
        properties.setValue(KEY_NOTIFICATIONS_MY_PIPELINES, uiState.notificationPreferences.myPipelinesOnly)
        properties.setValue(KEY_NOTIFICATIONS_STATUS, gson.toJson(uiState.notificationPreferences.statusFilter))
    }

    /**
     * Clear all persisted state.
     */
    fun clearAll() {
        properties.unsetValue(KEY_FILTERS)
        properties.unsetValue(KEY_SELECTED_PROJECTS)
        properties.unsetValue(KEY_UI_STATE)
        properties.unsetValue(KEY_BRANCH_FILTER)
        properties.unsetValue(KEY_MY_PIPELINES_ONLY)
        properties.unsetValue(KEY_STATUS_FILTER)
        properties.unsetValue(KEY_EXPANDED_ITEMS)
        properties.unsetValue(KEY_NOTIFICATIONS_ENABLED)
        properties.unsetValue(KEY_NOTIFICATIONS_MY_PIPELINES)
        properties.unsetValue(KEY_NOTIFICATIONS_STATUS)
    }
}
