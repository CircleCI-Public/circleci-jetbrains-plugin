package com.circleci.idea.context

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.api.clients.V3Page
import com.circleci.idea.api.models.ContextDetail
import com.circleci.idea.api.models.ContextRestriction
import com.circleci.idea.api.models.EnvVar
import com.circleci.idea.api.models.NamedEntity
import com.circleci.idea.api.models.RestrictionType
import com.circleci.idea.toolwindow.settings.Loadable
import com.circleci.idea.toolwindow.settings.SettingsApi
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a context page shows: the context, its environment variables, and its restrictions. */
data class ContextPageState(
    val context: Loadable<ContextDetail> = Loadable.NotLoaded,
    val envVars: Loadable<List<EnvVar>> = Loadable.NotLoaded,
    val restrictions: Loadable<List<ContextRestriction>> = Loadable.NotLoaded,
) {
    /** The context's restrictions of one type, as they're listed. */
    fun restrictions(type: RestrictionType): Loadable<List<ContextRestriction>> =
        when (restrictions) {
            is Loadable.Loaded -> Loadable.Loaded(restrictions.value.filter { it.type == type })
            else -> restrictions
        }
}

/**
 * A context page's data, and the changes it makes to the context.
 *
 * Everything loads as the page opens and on [refresh]; a list loads again
 * after a change to it, and the environment variables after a change made
 * elsewhere ([ContextPages.envVarChanges]). [scope] must run on the EDT, where the state
 * changes; the API calls switch to IO.
 */
class ContextPageModel(
    project: Project,
    private val ref: ContextRef,
    private val scope: CoroutineScope,
) {
    private val api = SettingsApi(project)
    private val pages = ContextPages.getInstance(project)

    private val _state = MutableStateFlow(ContextPageState())
    val state: StateFlow<ContextPageState> = _state.asStateFlow()

    private val requests = Requests()

    /** How many requests are in flight. */
    val loads: StateFlow<Int> = requests.count

    init {
        refresh()
        scope.launch {
            pages.envVarChanges.collect { change ->
                if (change.contextId == ref.id && change.source !== this@ContextPageModel) loadEnvVars()
            }
        }
    }

    /** Load everything again, leaving what's shown in place until it's done. */
    fun refresh() {
        loadContext()
        loadEnvVars()
        loadRestrictions()
    }

    /** Add an environment variable, or replace its value; then list them again. */
    suspend fun setEnvVar(
        name: String,
        value: String,
    ): Result<Unit> = changeEnvVars { setContextEnvVar(ref.id, name, value) }

    suspend fun deleteEnvVar(name: String): Result<Unit> = changeEnvVars { deleteContextEnvVar(ref.id, name) }

    /** Restrict the context, by a group's or project's ID or an expression; then list its restrictions again. */
    suspend fun addRestriction(
        type: RestrictionType,
        value: String,
    ): Result<Unit> = changeRestrictions { createContextRestriction(ref.id, type, value) }

    suspend fun deleteRestriction(restriction: ContextRestriction): Result<Unit> =
        changeRestrictions { deleteContextRestriction(ref.id, restriction.id) }

    /** The ID of the context's organization, which is also the group of all its members. */
    suspend fun orgId(): Result<String> {
        (_state.value.context as? Loadable.Loaded)?.value?.orgId?.let { return Result.success(it) }
        return requests.counted { api.call { getContext(ref.id) } }
            .mapCatching { it.orgId ?: error("The context ${ref.name} has no organization") }
    }

    /** The organization's groups, to restrict the context to. */
    suspend fun groups(): Result<List<NamedEntity>> {
        val orgId = orgId().getOrElse { return Result.failure(it) }
        return requests.counted { api.call { listGroups(orgId) } }
    }

    /** A page of the organization's projects whose names contain [name], to restrict the context to. */
    suspend fun searchProjects(
        name: String,
        cursor: String? = null,
    ): Result<V3Page<NamedEntity>> {
        val orgId = orgId().getOrElse { return Result.failure(it) }
        return api.call { searchProjects(orgId, name, cursor) }
    }

    private suspend fun changeEnvVars(change: CircleCIApiService.() -> Result<Unit>): Result<Unit> {
        val result = requests.counted { api.call(change) }
        // Listed again whether or not it worked: a failure may still have changed something.
        loadEnvVars()
        pages.envVarsChanged(EnvVarsChanged(ref.id, this))
        return result
    }

    private suspend fun changeRestrictions(change: CircleCIApiService.() -> Result<Unit>): Result<Unit> {
        val result = requests.counted { api.call(change) }
        loadRestrictions()
        return result
    }

    private fun loadContext() {
        load({ api.call { getContext(ref.id) } }) { it.copy(context = keep(it.context, this)) }
    }

    private fun loadEnvVars() {
        load({ api.call { listContextEnvVars(ref.id) } }) { it.copy(envVars = keep(it.envVars, this)) }
    }

    private fun loadRestrictions() {
        load({ api.call { listContextRestrictions(ref.id) } }) { it.copy(restrictions = keep(it.restrictions, this)) }
    }

    /**
     * Fetch something the page shows, and [set] it in the state: as
     * loading first, unless it's shown already, then as it loaded.
     */
    private fun <T> load(
        fetch: suspend () -> Result<T>,
        set: Loadable<T>.(ContextPageState) -> ContextPageState,
    ) {
        _state.update { Loadable.Loading.set(it) }
        scope.launch {
            val loaded =
                requests.counted { fetch() }.fold(
                    { Loadable.Loaded(it) },
                    { Loadable.Failed(it.message ?: "Failed to load") },
                )
            _state.update { loaded.set(it) }
        }
    }
}

/** The requests in flight. */
private class Requests {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    /** [block], counted until it ends, however it ends. */
    suspend fun <T> counted(block: suspend () -> T): T {
        _count.update { it + 1 }
        try {
            return block()
        } finally {
            _count.update { it - 1 }
        }
    }
}

/** [next], except that while it's still loading, what's shown stays. */
private fun <T> keep(
    shown: Loadable<T>,
    next: Loadable<T>,
): Loadable<T> = if (next == Loadable.Loading && shown is Loadable.Loaded) shown else next
