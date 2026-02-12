package com.circleci.idea.api.clients

import com.circleci.idea.api.CircleCIApiClient
import com.circleci.idea.api.models.ProjectInfo
import com.circleci.idea.api.models.UserInfo
import com.google.gson.reflect.TypeToken

/**
 * API client for project and user-related operations.
 * Handles fetching user information and followed projects.
 */
class ProjectApiClient : CircleCIApiClientBase() {
    /**
     * Get current user information.
     * Used for token validation and getting user ID.
     *
     * @param client The initialized API client
     * @return User information
     */
    fun getCurrentUser(client: CircleCIApiClient): Result<UserInfo> {
        return executeRequest(client, "/api/v2/me") { data ->
            gson.fromJson(data.toString(), UserInfo::class.java)
        }
    }

    /**
     * Get followed projects.
     *
     * @param client The initialized API client
     * @return List of followed projects
     */
    fun getFollowedProjects(client: CircleCIApiClient): Result<List<ProjectInfo>> {
        return executeRequest(client, "/api/v1.1/projects") { data ->
            gson.fromJson(data.toString(), object : TypeToken<List<ProjectInfo>>() {}.type)
        }
    }
}
