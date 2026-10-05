package com.circleci.idea.api.clients

import com.circleci.idea.api.CircleCIApiClient
import com.circleci.idea.api.models.ContextDetail
import com.circleci.idea.api.models.ContextRestriction
import com.circleci.idea.api.models.ContextRestrictionWire
import com.circleci.idea.api.models.ContextWire
import com.circleci.idea.api.models.NamedEntity
import com.circleci.idea.api.models.NamedEntityWire
import com.circleci.idea.api.models.RestrictionType
import com.circleci.idea.api.models.V3List
import com.google.gson.reflect.TypeToken

/**
 * API client for a context's page: the context, its restrictions, and the
 * groups and projects to restrict it to, all on v3. Its environment
 * variables are [SettingsApiClient]'s.
 */
class ContextApiClient : CircleCIApiClientBase() {
    /** A context, with the organization it's in. */
    fun getContext(
        client: CircleCIApiClient,
        contextId: String,
    ): Result<ContextDetail> {
        return executeRequest(client, "/api/v3/contexts/$contextId") { data ->
            val wire = gson.fromJson(data.getAsJsonObject("data"), ContextWire::class.java)
            val id = wire.id ?: error("The context has no ID")
            ContextDetail(
                id = id,
                name = wire.attributes?.name ?: id,
                orgId = wire.references?.org?.id,
                createdAt = instant(wire.attributes?.createdAt),
            )
        }
    }

    /** A context's restrictions, of the types known here. The list isn't paged. */
    fun listContextRestrictions(
        client: CircleCIApiClient,
        contextId: String,
    ): Result<List<ContextRestriction>> {
        val params = mapOf("filter[context_id]" to contextId)
        return executeRequest(client, "/api/v3/context-restrictions", params) { data ->
            val list: V3List<ContextRestrictionWire> =
                gson.fromJson(data, object : TypeToken<V3List<ContextRestrictionWire>>() {}.type)
            list.data.orEmpty().mapNotNull { wire ->
                val attributes = wire.attributes ?: return@mapNotNull null
                ContextRestriction(
                    id = wire.id ?: return@mapNotNull null,
                    type = RestrictionType.of(attributes.restrictionType) ?: return@mapNotNull null,
                    value = attributes.matchPattern ?: return@mapNotNull null,
                    name = attributes.name?.takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    /** Restrict a context to a group, a project or an expression, by [value] (see [ContextRestriction.value]). */
    fun createContextRestriction(
        client: CircleCIApiClient,
        contextId: String,
        type: RestrictionType,
        value: String,
    ): Result<Unit> {
        val body =
            mapOf(
                "data" to
                    mapOf(
                        "attributes" to mapOf("restriction_type" to type.wireName, "match_pattern" to value),
                        "references" to mapOf("context" to mapOf("id" to contextId)),
                    ),
            )
        return executePostRequest(client, "/api/v3/context-restrictions", body)
    }

    /** Remove one of a context's restrictions; its ID is only unique within the context. */
    fun deleteContextRestriction(
        client: CircleCIApiClient,
        contextId: String,
        restrictionId: String,
    ): Result<Unit> {
        return executeDeleteRequest(
            client,
            "/api/v3/context-restrictions/$restrictionId",
            mapOf("filter[context_id]" to contextId),
        )
    }

    /** An organization's groups: its VCS's teams, or a standalone organization's own. The list isn't paged. */
    fun listGroups(
        client: CircleCIApiClient,
        orgId: String,
    ): Result<List<NamedEntity>> {
        return executeRequest(client, "/api/v3/groups", mapOf("filter[org_id]" to orgId)) { data ->
            named(gson.fromJson(data, object : TypeToken<V3List<NamedEntityWire>>() {}.type))
        }
    }

    /** A page of an organization's projects whose names contain [name], by name, from [cursor]. */
    fun searchProjects(
        client: CircleCIApiClient,
        orgId: String,
        name: String,
        cursor: String?,
    ): Result<V3Page<NamedEntity>> {
        val params =
            buildMap {
                put("filter[org_id]", orgId)
                if (name.isNotBlank()) put("filter[name]", name.trim())
                put("order_by", "name")
                put("page[limit]", PROJECT_PAGE_LIMIT.toString())
                cursor?.let { put("page[cursor]", it) }
            }
        return executeRequest(client, "/api/v3/projects", params) { data ->
            val list: V3List<NamedEntityWire> =
                gson.fromJson(
                    data,
                    object : TypeToken<V3List<NamedEntityWire>>() {}.type,
                )
            V3Page(named(list), list.page?.next?.takeIf { it.isNotEmpty() })
        }
    }

    private fun named(list: V3List<NamedEntityWire>): List<NamedEntity> =
        list.data.orEmpty().mapNotNull { wire -> wire.id?.let { NamedEntity(it, wire.attributes?.name ?: it) } }

    private companion object {
        // The API's maximum.
        const val PROJECT_PAGE_LIMIT = 50
    }
}
