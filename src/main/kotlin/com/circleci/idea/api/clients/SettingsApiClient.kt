package com.circleci.idea.api.clients

import com.circleci.idea.api.CircleCIApiClient
import com.circleci.idea.api.models.Context
import com.circleci.idea.api.models.ContextEnvVarWire
import com.circleci.idea.api.models.ContextWire
import com.circleci.idea.api.models.EnvVar
import com.circleci.idea.api.models.EnvVarRequest
import com.circleci.idea.api.models.ProjectEnvVarPageWire
import com.circleci.idea.api.models.V3List
import com.google.gson.reflect.TypeToken

/**
 * API client for project environment variables, and for contexts and their
 * environment variables.
 *
 * Project environment variables are on v2, as v3 has no route for them yet;
 * contexts are on v3. Values are only ever written: the API masks them when
 * it lists them.
 */
class SettingsApiClient : CircleCIApiClientBase() {
    /** All of a project's environment variables. */
    fun listProjectEnvVars(
        client: CircleCIApiClient,
        projectSlug: String,
    ): Result<List<EnvVar>> {
        return fetchAllPages { token ->
            val params = token?.let { mapOf("page-token" to it) }.orEmpty()
            executeRequest(client, "/api/v2/project/$projectSlug/envvar", params) { data ->
                val page = gson.fromJson(data, ProjectEnvVarPageWire::class.java)
                val vars = page.items.orEmpty().mapNotNull { v -> v.name?.let { EnvVar(it, v.value.orEmpty()) } }
                V3Page(vars, page.nextPageToken?.takeIf { it.isNotEmpty() })
            }
        }
    }

    /** Add a project environment variable, or replace its value. */
    fun setProjectEnvVar(
        client: CircleCIApiClient,
        projectSlug: String,
        name: String,
        value: String,
    ): Result<Unit> {
        return executePostRequest(client, "/api/v2/project/$projectSlug/envvar", EnvVarRequest(name, value))
    }

    fun deleteProjectEnvVar(
        client: CircleCIApiClient,
        projectSlug: String,
        name: String,
    ): Result<Unit> {
        return executeDeleteRequest(client, "/api/v2/project/$projectSlug/envvar/$name")
    }

    /** A page of an organization's contexts, from [cursor] (the first page at null). */
    fun listContexts(
        client: CircleCIApiClient,
        orgId: String,
        cursor: String?,
    ): Result<V3Page<Context>> {
        val params = pageParams("filter[org_id]" to orgId, cursor, CONTEXT_PAGE_LIMIT)
        return executeRequest(client, "/api/v3/contexts", params) { data ->
            val list: V3List<ContextWire> = gson.fromJson(data, object : TypeToken<V3List<ContextWire>>() {}.type)
            V3Page(list.data.orEmpty().mapNotNull(::context), list.page?.next?.takeIf { it.isNotEmpty() })
        }
    }

    /** Create a context in an organization. */
    fun createContext(
        client: CircleCIApiClient,
        orgId: String,
        name: String,
    ): Result<Context> {
        val body =
            mapOf(
                "data" to
                    mapOf(
                        "attributes" to mapOf("name" to name),
                        "references" to mapOf("org" to mapOf("id" to orgId)),
                    ),
            )
        return executeRequest(client, "/api/v3/contexts", body = body) { data ->
            val created = gson.fromJson(data.getAsJsonObject("data"), ContextWire::class.java)
            context(created) ?: error("The created context has no ID")
        }
    }

    /** All of a context's environment variables. */
    fun listContextEnvVars(
        client: CircleCIApiClient,
        contextId: String,
    ): Result<List<EnvVar>> {
        return fetchAllPages { cursor ->
            executeRequest(
                client,
                "/api/v3/contexts/$contextId/env-vars",
                pageParams(null, cursor, PAGE_LIMIT),
            ) { data ->
                val list: V3List<ContextEnvVarWire> =
                    gson.fromJson(data, object : TypeToken<V3List<ContextEnvVarWire>>() {}.type)
                val vars =
                    list.data.orEmpty().mapNotNull { v ->
                        v.attributes?.name?.let {
                            EnvVar(
                                it,
                                MASK + v.attributes.truncatedValue.orEmpty(),
                                instant(v.attributes.createdAt),
                                instant(v.attributes.updatedAt),
                            )
                        }
                    }
                V3Page(vars, list.page?.next?.takeIf { it.isNotEmpty() })
            }
        }
    }

    /** Add a context environment variable, or replace its value. */
    fun setContextEnvVar(
        client: CircleCIApiClient,
        contextId: String,
        name: String,
        value: String,
    ): Result<Unit> {
        return executePostRequest(client, "/api/v3/contexts/$contextId/env-vars/set", EnvVarRequest(name, value))
    }

    /** Delete a context environment variable; it's named by a filter, not in the path. */
    fun deleteContextEnvVar(
        client: CircleCIApiClient,
        contextId: String,
        name: String,
    ): Result<Unit> {
        return executeDeleteRequest(client, "/api/v3/contexts/$contextId/env-vars", mapOf("filter[name]" to name))
    }

    private fun context(wire: ContextWire): Context? = wire.id?.let { Context(it, wire.attributes?.name ?: it) }

    private fun pageParams(
        filter: Pair<String, String>?,
        cursor: String?,
        limit: Int,
    ): Map<String, String> {
        return buildMap {
            filter?.let { put(it.first, it.second) }
            put("page[limit]", limit.toString())
            cursor?.let { put("page[cursor]", it) }
        }
    }

    private companion object {
        // The most a v3 page holds.
        const val PAGE_LIMIT = 100

        const val CONTEXT_PAGE_LIMIT = 20

        // Before a context variable's last few characters, as the CLI shows them.
        const val MASK = "****"
    }
}
