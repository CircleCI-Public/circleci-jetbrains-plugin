package com.circleci.idea.toolwindow.settings

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.api.models.EnvVar
import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.project.CircleCIProjectService
import com.circleci.idea.settings.CircleCISettings
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.tree.TreeState

/**
 * The settings tree's data: the selected project's environment variables,
 * and its organization's contexts with theirs.
 *
 * Each list loads when its node is first opened, and again after a change
 * to it. Selecting another project starts over. [scope] must run on the
 * EDT, where the state changes; the API calls switch to IO.
 */
class SettingsTreeModel(
    project: Project,
    private val scope: CoroutineScope,
) {
    private val projectService = project.getService(CircleCIProjectService::class.java)
    private val authService = CircleCIAuthService.getInstance(project)
    private val apiService = CircleCIApiService.getInstance()
    private val settings = CircleCISettings.getInstance()

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** How far the tree is scrolled, shared with its scrollbar. */
    val scroll = LazyListState()

    /** What's open and selected in the tree, by node key. The lists load on opening. */
    val treeState = TreeState(SelectableLazyListState(scroll))

    init {
        scope.launch {
            projectService.selectedProject.collect { slug ->
                _state.value = SettingsState(projectSlug = slug)
                loadOpened(treeState.openNodes)
            }
        }

        // Load what's opened; forget a failed load on closing, to retry on reopening.
        scope.launch {
            var previous = emptySet<Any>()
            snapshotFlow { treeState.openNodes }.collect { open ->
                forgetFailedLoads(previous - open)
                previous = open
                loadOpened(open)
            }
        }
    }

    /** Load every list again, as the open nodes show them. */
    fun refresh() {
        _state.value = SettingsState(projectSlug = _state.value.projectSlug)
        loadOpened(treeState.openNodes)
    }

    /** List [owner]'s environment variables again, opening its node to show them. */
    fun refreshEnvVars(owner: EnvVarOwner) {
        loadEnvVars(owner)
        treeState.openNodes += owner.key
    }

    /** List the selected project's environment variables again. */
    fun refreshProjectEnvVars() {
        _state.value.projectSlug?.let { refreshEnvVars(EnvVarOwner.Project(it)) }
    }

    /** List the organization's contexts again, and the variables of those open. */
    fun refreshContexts() {
        val slug = _state.value.projectSlug ?: return
        loadContexts(slug)
        treeState.openNodes += CONTEXTS_KEY
    }

    /** Add an environment variable to [owner], or replace its value; then list its variables again. */
    suspend fun setEnvVar(
        owner: EnvVarOwner,
        name: String,
        value: String,
    ): Result<Unit> =
        changeEnvVars(owner) {
            when (owner) {
                is EnvVarOwner.Project -> apiService.setProjectEnvVar(owner.slug, name, value)
                is EnvVarOwner.OrgContext -> apiService.setContextEnvVar(owner.context.id, name, value)
            }
        }

    /** Delete one of [owner]'s environment variables; then list them again. */
    suspend fun deleteEnvVar(
        owner: EnvVarOwner,
        name: String,
    ): Result<Unit> =
        changeEnvVars(owner) {
            when (owner) {
                is EnvVarOwner.Project -> apiService.deleteProjectEnvVar(owner.slug, name)
                is EnvVarOwner.OrgContext -> apiService.deleteContextEnvVar(owner.context.id, name)
            }
        }

    private suspend fun changeEnvVars(
        owner: EnvVarOwner,
        change: () -> Result<Unit>,
    ): Result<Unit> {
        val result = authenticated { change() }
        // Listed again whether or not it worked: a failure may still have changed something.
        refreshEnvVars(owner)
        return result
    }

    private fun loadOpened(open: Set<Any>) {
        val current = _state.value
        val slug = current.projectSlug ?: return
        if (PROJECT_ENV_VARS_KEY in open && current.projectEnvVars == Loadable.NotLoaded) {
            loadEnvVars(EnvVarOwner.Project(slug))
        }
        if (CONTEXTS_KEY in open && current.contexts == Loadable.NotLoaded) {
            loadContexts(slug)
        }
        val contexts = (current.contexts as? Loadable.Loaded)?.value.orEmpty()
        contexts.map(EnvVarOwner::OrgContext)
            .filter { it.key in open && current.contextEnvVars[it.context.id] == null }
            .forEach(::loadEnvVars)
    }

    private fun loadEnvVars(owner: EnvVarOwner) {
        val slug = _state.value.projectSlug ?: return
        setEnvVars(owner, Loadable.Loading)
        scope.launch {
            val result =
                authenticated {
                    when (owner) {
                        is EnvVarOwner.Project -> apiService.listProjectEnvVars(owner.slug)
                        is EnvVarOwner.OrgContext -> apiService.listContextEnvVars(owner.context.id)
                    }
                }
            // Dropped if another project was selected meanwhile.
            if (_state.value.projectSlug == slug) setEnvVars(owner, result.toLoadable())
        }
    }

    private fun loadContexts(slug: String) {
        _state.update { it.copy(contexts = Loadable.Loading) }
        scope.launch {
            val result =
                authenticated {
                    apiService.getProjectLink(slug).mapCatching { link ->
                        link.orgId ?: error("Project $slug has no organization")
                    }.mapCatching { orgId -> apiService.listContexts(orgId).getOrThrow() }
                }
            if (_state.value.projectSlug == slug) {
                _state.update { it.copy(contexts = result.toLoadable(), contextEnvVars = emptyMap()) }
                // Contexts left open from before load their variables too.
                loadOpened(treeState.openNodes)
            }
        }
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

    /** Set the lists of nodes just closed back to unloaded if they failed, so opening them again retries. */
    private fun forgetFailedLoads(closed: Set<Any>) {
        _state.update { state ->
            val contextEnvVars =
                state.contextEnvVars.filterNot {
                        (id, vars) ->
                    vars is Loadable.Failed && contextKey(id) in closed
                }
            state.copy(
                projectEnvVars = state.projectEnvVars.forgetIfFailed(PROJECT_ENV_VARS_KEY in closed),
                contexts = state.contexts.forgetIfFailed(CONTEXTS_KEY in closed),
                contextEnvVars = contextEnvVars,
            )
        }
    }

    /** Run [call] on IO with the API client set up, or fail if not logged in. */
    private suspend fun <T> authenticated(call: () -> Result<T>): Result<T> =
        withContext(Dispatchers.IO) {
            val token = authService.getToken()
            if (!authService.isAuthenticated() || token == null) {
                return@withContext Result.failure(IllegalStateException("Not logged in to CircleCI"))
            }
            apiService.initialize(token, settings.hostUrl)
            try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (
                // Whatever went wrong, it's a failed request to show, not a crash.
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Result.failure(e)
            }
        }
}

private fun <T> Loadable<T>.forgetIfFailed(closed: Boolean): Loadable<T> =
    if (closed && this is Loadable.Failed) Loadable.NotLoaded else this

private fun <T> Result<T>.toLoadable(): Loadable<T> =
    fold({ Loadable.Loaded(it) }, { Loadable.Failed(it.message ?: "Failed to load") })
