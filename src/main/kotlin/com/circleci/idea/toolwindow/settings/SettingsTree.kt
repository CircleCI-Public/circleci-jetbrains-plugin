package com.circleci.idea.toolwindow.settings

import com.circleci.idea.api.models.Context
import com.circleci.idea.api.models.EnvVar
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.TreeGeneratorScope
import org.jetbrains.jewel.foundation.lazy.tree.buildTree

/*
 * The settings sections' trees as Jewel's tree draws them, built from what
 * the model has loaded: the selected project's environment variables in
 * one, and its organization's contexts, with theirs, in the other. Rows are
 * keyed by what they show, so what's open and selected carries over a reload.
 */

/** A list that may not have loaded yet, or failed to. */
sealed interface Loadable<out T> {
    data object NotLoaded : Loadable<Nothing>

    data object Loading : Loadable<Nothing>

    data class Loaded<T>(val value: T) : Loadable<T>

    data class Failed(val message: String) : Loadable<Nothing>
}

/**
 * What the settings sections show, for the project selected (if any).
 *
 * @property moreContexts Whether there's another page of contexts to load
 * @property moreContextsError Why the last page of contexts failed to load, until one loads
 */
data class SettingsState(
    val projectSlug: String? = null,
    val projectEnvVars: Loadable<List<EnvVar>> = Loadable.NotLoaded,
    val contexts: Loadable<List<Context>> = Loadable.NotLoaded,
    val moreContexts: Boolean = false,
    val moreContextsError: String? = null,
    val contextEnvVars: Map<String, Loadable<List<EnvVar>>> = emptyMap(),
)

/** Whose environment variables these are: a project's, or a context's. */
sealed interface EnvVarOwner {
    val key: String

    data class Project(val slug: String) : EnvVarOwner {
        override val key = PROJECT_ENV_VARS_KEY
    }

    data class OrgContext(val context: Context) : EnvVarOwner {
        override val key = contextKey(context.id)
    }
}

/** A row of a settings tree. */
sealed interface SettingsNode {
    val key: String
    val label: String

    /** A context, its environment variables listed underneath. */
    data class EnvVars(val owner: EnvVarOwner.OrgContext) : SettingsNode {
        override val key = owner.key
        override val label = owner.context.name
    }

    data class Variable(val owner: EnvVarOwner, val envVar: EnvVar) : SettingsNode {
        override val key = "${owner.key}/var:${envVar.name}"
        override val label = envVar.name
    }

    /** Loading, empty or error rows, by their place under their parent. */
    data class Message(
        override val key: String,
        override val label: String,
        val error: Boolean = false,
    ) : SettingsNode

    /** After the contexts listed, while there are more: loading the next page, or why it failed. */
    data class MoreContexts(val error: String?) : SettingsNode {
        override val key = "$CONTEXTS_KEY/more"
        override val label =
            error?.let { "Couldn't load more contexts: $it. Click to retry" } ?: "Loading more contexts..."
    }
}

internal const val PROJECT_ENV_VARS_KEY = "project-env-vars"
internal const val CONTEXTS_KEY = "contexts"

/** The key of a context's node, which lists its environment variables. */
internal fun contextKey(contextId: String): String = "context:$contextId"

/** The project section's tree for [state]: its environment variables. */
internal fun projectSettingsTree(state: SettingsState): Tree<SettingsNode> =
    buildTree {
        val slug = state.projectSlug ?: return@buildTree addNoProject()
        val owner = EnvVarOwner.Project(slug)
        addList(owner.key, state.projectEnvVars, "No environment variables") { envVar ->
            val node = SettingsNode.Variable(owner, envVar)
            addLeaf(node, node.key)
        }
    }

/** The organization section's tree for [state]: its contexts, each opening to its environment variables. */
internal fun orgSettingsTree(state: SettingsState): Tree<SettingsNode> =
    buildTree {
        if (state.projectSlug == null) return@buildTree addNoProject()
        addList(CONTEXTS_KEY, state.contexts, "No contexts") { context ->
            val owner = EnvVarOwner.OrgContext(context)
            addNode(SettingsNode.EnvVars(owner), owner.key) {
                addList(owner.key, state.contextEnvVars[context.id] ?: Loadable.NotLoaded, "No environment variables") {
                    val node = SettingsNode.Variable(owner, it)
                    addLeaf(node, node.key)
                }
            }
        }
        if (state.contexts is Loadable.Loaded && state.moreContexts) {
            val more = SettingsNode.MoreContexts(state.moreContextsError)
            addLeaf(more, more.key)
        }
    }

private fun TreeGeneratorScope<SettingsNode>.addNoProject() {
    addLeaf(SettingsNode.Message("no-project", "No CircleCI project selected"), "no-project")
}

/** A list's items once loaded, or a row saying it's loading, empty or failed. */
private fun <T> TreeGeneratorScope<SettingsNode>.addList(
    parentKey: String,
    list: Loadable<List<T>>,
    emptyText: String,
    addItem: TreeGeneratorScope<SettingsNode>.(T) -> Unit,
) {
    val messageKey = "$parentKey/message"
    when (list) {
        Loadable.NotLoaded, Loadable.Loading -> addLeaf(SettingsNode.Message(messageKey, "Loading..."), messageKey)
        is Loadable.Failed -> addLeaf(SettingsNode.Message(messageKey, list.message, error = true), messageKey)
        is Loadable.Loaded ->
            if (list.value.isEmpty()) {
                addLeaf(SettingsNode.Message(messageKey, emptyText), messageKey)
            } else {
                list.value.forEach { addItem(it) }
            }
    }
}
