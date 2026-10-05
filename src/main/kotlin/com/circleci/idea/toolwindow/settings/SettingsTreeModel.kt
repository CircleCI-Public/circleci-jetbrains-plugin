package com.circleci.idea.toolwindow.settings

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.api.models.Context
import com.circleci.idea.api.models.EnvVar
import com.circleci.idea.context.ContextPages
import com.circleci.idea.context.EnvVarsChanged
import com.circleci.idea.project.CircleCIProjectService
import com.circleci.idea.state.PagedList
import com.circleci.idea.toolwindow.isNearEnd
import com.circleci.idea.toolwindow.scrolledOrResized
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.tree.TreeState

/**
 * The settings sections' data: the selected project's environment
 * variables, and its organization's contexts with theirs.
 *
 * The project's variables and the contexts load as the project is
 * selected, the contexts a page at a time as their list scrolls near its
 * end. A context's variables load as it's first opened. Each list loads
 * again after a change to it, here or on a context's page, and selecting
 * another project starts over.
 * [scope] must run on the EDT, where the state changes; the API calls
 * switch to IO.
 */
class SettingsTreeModel(
    project: Project,
    private val scope: CoroutineScope,
) {
    private val projectService = project.getService(CircleCIProjectService::class.java)
    private val pages = ContextPages.getInstance(project)
    private val api = SettingsApi(project)

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    val projectTree = SectionTree()

    /** The organization section's tree, whose contexts load their variables on opening. */
    val orgTree = SectionTree()

    // The selected project's contexts, replaced as another is selected.
    private var contexts: PagedList<Context, String>? = null

    // Bumped as the contexts start loading afresh, so a load it supersedes can tell.
    private var contextLoads = 0

    init {
        scope.launch {
            projectService.selectedProject.collect { slug ->
                _state.value = SettingsState(projectSlug = slug)
                contexts = null
                loadShown()
            }
        }

        // Load the variables of contexts opened; forget a failed load on closing, to retry on reopening.
        scope.launch {
            var previous = emptySet<Any>()
            snapshotFlow { orgTree.treeState.openNodes }.collect { open ->
                forgetFailedLoads(previous - open)
                previous = open
                loadShown()
            }
        }

        scope.launch { orgTree.scroll.scrolledOrResized().collect { loadMoreContextsIfNearEnd() } }

        scope.launch {
            pages.envVarChanges.collect { change ->
                val listed = change.contextId in _state.value.contextEnvVars
                if (change.source === this@SettingsTreeModel || !listed) return@collect
                val contexts = (_state.value.contexts as? Loadable.Loaded)?.value.orEmpty()
                contexts.firstOrNull { it.id == change.contextId }?.let { loadEnvVars(EnvVarOwner.OrgContext(it)) }
            }
        }
    }

    /** Load every list again. */
    fun refresh() {
        _state.value = SettingsState(projectSlug = _state.value.projectSlug)
        contexts = null
        loadShown()
    }

    /** List [owner]'s environment variables again; a context's node opens to show them. */
    fun refreshEnvVars(owner: EnvVarOwner) {
        loadEnvVars(owner)
        if (owner is EnvVarOwner.OrgContext) orgTree.treeState.openNodes += owner.key
    }

    /** List the organization's contexts again, as many pages as are listed, and the variables of those open. */
    fun refreshContexts() {
        loadContexts(refresh = true)
    }

    /** Load the next page of contexts. Once one has failed, this retries it. */
    fun loadMoreContexts() {
        val list = contexts ?: return
        if (!_state.value.moreContexts || list.state.value.loadingMore) return
        _state.update { it.copy(moreContextsError = null) }
        scope.launch {
            val result = orgTree.loading { list.loadMore() } ?: return@launch
            if (list !== contexts) return@launch
            val paged = list.state.value
            _state.update {
                it.copy(
                    contexts = Loadable.Loaded(paged.items),
                    moreContexts = paged.hasMore,
                    moreContextsError = result.exceptionOrNull()?.let { error -> error.message ?: "unknown error" },
                )
            }
        }
    }

    /** Create a context in the selected project's organization, then list the contexts again. */
    suspend fun createContext(name: String): Result<Context> {
        val slug = _state.value.projectSlug ?: return Result.failure(IllegalStateException("No project selected"))
        val result = orgTree.loading { api.inOrg(slug) { createContext(it, name) } }
        refreshContexts()
        return result
    }

    /** Add an environment variable to [owner], or replace its value; then list its variables again. */
    suspend fun setEnvVar(
        owner: EnvVarOwner,
        name: String,
        value: String,
    ): Result<Unit> =
        changeEnvVars(owner) {
            when (owner) {
                is EnvVarOwner.Project -> setProjectEnvVar(owner.slug, name, value)
                is EnvVarOwner.OrgContext -> setContextEnvVar(owner.context.id, name, value)
            }
        }

    /** Delete one of [owner]'s environment variables; then list them again. */
    suspend fun deleteEnvVar(
        owner: EnvVarOwner,
        name: String,
    ): Result<Unit> =
        changeEnvVars(owner) {
            when (owner) {
                is EnvVarOwner.Project -> deleteProjectEnvVar(owner.slug, name)
                is EnvVarOwner.OrgContext -> deleteContextEnvVar(owner.context.id, name)
            }
        }

    private suspend fun changeEnvVars(
        owner: EnvVarOwner,
        change: CircleCIApiService.() -> Result<Unit>,
    ): Result<Unit> {
        val tree = if (owner is EnvVarOwner.Project) projectTree else orgTree
        val result = tree.loading { api.call(change) }
        // Listed again whether or not it worked: a failure may still have changed something.
        refreshEnvVars(owner)
        if (owner is EnvVarOwner.OrgContext) pages.envVarsChanged(EnvVarsChanged(owner.context.id, this))
        return result
    }

    private fun loadShown() {
        val current = _state.value
        val slug = current.projectSlug ?: return
        if (current.projectEnvVars == Loadable.NotLoaded) loadEnvVars(EnvVarOwner.Project(slug))
        if (current.contexts == Loadable.NotLoaded) loadContexts(refresh = false)
        val open = orgTree.treeState.openNodes
        val contexts = (current.contexts as? Loadable.Loaded)?.value.orEmpty()
        contexts.map(EnvVarOwner::OrgContext)
            .filter { it.key in open && current.contextEnvVars[it.context.id] == null }
            .forEach(::loadEnvVars)
    }

    private fun loadEnvVars(owner: EnvVarOwner) {
        val current = _state.value
        val slug = current.projectSlug ?: return
        val listed =
            when (owner) {
                is EnvVarOwner.Project -> current.projectEnvVars
                is EnvVarOwner.OrgContext -> current.contextEnvVars[owner.context.id]
            }
        // Loading again leaves what's listed in place until it's done.
        if (listed !is Loadable.Loaded) setEnvVars(owner, Loadable.Loading)
        val tree = if (owner is EnvVarOwner.Project) projectTree else orgTree
        scope.launch {
            val result =
                tree.loading {
                    api.call {
                        when (owner) {
                            is EnvVarOwner.Project -> listProjectEnvVars(owner.slug)
                            is EnvVarOwner.OrgContext -> listContextEnvVars(owner.context.id)
                        }
                    }
                }
            // Dropped if another project was selected meanwhile.
            if (_state.value.projectSlug == slug) setEnvVars(owner, result.toLoadable())
        }
    }

    /** List the first page of contexts afresh, or with [refresh], as many as are listed. */
    private fun loadContexts(refresh: Boolean) {
        val slug = _state.value.projectSlug ?: return
        val list = contexts.takeIf { refresh } ?: api.contextPages(slug).also { contexts = it }
        val load = ++contextLoads
        // A refresh leaves what's listed in place until it's done.
        if (_state.value.contexts !is Loadable.Loaded) _state.update { it.copy(contexts = Loadable.Loading) }
        scope.launch {
            val result = orgTree.loading { if (refresh) list.refresh() else list.reload() }
            if (list !== contexts || load != contextLoads) return@launch
            val paged = list.state.value
            _state.update {
                it.copy(
                    contexts = result.map { paged.items }.toLoadable(),
                    moreContexts = paged.hasMore,
                    moreContextsError = null,
                    contextEnvVars = emptyMap(),
                )
            }
            // Contexts left open load their variables again.
            loadShown()
            loadMoreContextsIfNearEnd()
        }
    }

    private fun loadMoreContextsIfNearEnd() {
        val current = _state.value
        if (current.contexts !is Loadable.Loaded || !current.moreContexts || current.moreContextsError != null) return
        if (orgTree.scroll.isNearEnd()) loadMoreContexts()
    }

    private fun setEnvVars(
        owner: EnvVarOwner,
        vars: Loadable<List<EnvVar>>,
    ) {
        _state.update {
            when (owner) {
                is EnvVarOwner.Project -> it.copy(projectEnvVars = vars)
                is EnvVarOwner.OrgContext -> it.copy(contextEnvVars = it.contextEnvVars + (owner.context.id to vars))
            }
        }
    }

    /** Set the variables of contexts just closed back to unloaded if they failed, so opening them again retries. */
    private fun forgetFailedLoads(closed: Set<Any>) {
        _state.update { state ->
            val kept = state.contextEnvVars.filterNot { it.value is Loadable.Failed && contextKey(it.key) in closed }
            state.copy(contextEnvVars = kept)
        }
    }
}

private fun <T> Result<T>.toLoadable(): Loadable<T> =
    fold({ Loadable.Loaded(it) }, { Loadable.Failed(it.message ?: "Failed to load") })

/** A section's tree's state, and the loads that fill it. */
class SectionTree {
    val scroll = LazyListState()
    val treeState = TreeState(SelectableLazyListState(scroll))

    private val _loads = MutableStateFlow(0)

    /** How many of its lists are loading or refreshing. */
    val loads: StateFlow<Int> = _loads.asStateFlow()

    /** [block], counted among [loads] until it ends, however it ends. */
    internal suspend fun <T> loading(block: suspend () -> T): T {
        _loads.update { it + 1 }
        try {
            return block()
        } finally {
            _loads.update { it - 1 }
        }
    }
}
