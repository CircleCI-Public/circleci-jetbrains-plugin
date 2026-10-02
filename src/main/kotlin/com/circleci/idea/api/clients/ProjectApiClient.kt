package com.circleci.idea.api.clients

import com.circleci.idea.api.CircleCIApiClient
import com.circleci.idea.api.models.UserInfo
import com.circleci.idea.api.models.UserWire
import com.circleci.idea.api.models.V3List
import com.google.gson.reflect.TypeToken

/**
 * API client for user-related operations.
 */
class ProjectApiClient : CircleCIApiClientBase() {
    /**
     * Get current user information, via GET /api/v3/users?filter[user_id]=me.
     * Used for token validation and getting user ID.
     *
     * @param client The initialized API client
     * @return User information
     */
    fun getCurrentUser(client: CircleCIApiClient): Result<UserInfo> {
        return executeRequest(client, "/api/v3/users", mapOf("filter[user_id]" to "me")) { data ->
            val users: V3List<UserWire> = gson.fromJson(data, object : TypeToken<V3List<UserWire>>() {}.type)
            val user = users.data?.firstOrNull() ?: error("No user in the response")
            val id = user.id ?: error("The user has no ID")
            val login = user.attributes?.login ?: error("The user has no login")
            UserInfo(id = id, login = login, name = user.attributes.name, avatarUrl = user.attributes.avatarUrl)
        }
    }
}
