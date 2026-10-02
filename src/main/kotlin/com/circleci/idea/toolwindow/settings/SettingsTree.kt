package com.circleci.idea.toolwindow.settings

import com.circleci.idea.api.models.Context
import com.circleci.idea.api.models.EnvVar
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.TreeGeneratorScope
import org.jetbrains.jewel.foundation.lazy.tree.buildTree

/*
 * The settings tree as Jewel's tree draws it, built from what the model has
 * loaded: the selected project's environment variables, and its
 * organization's contexts with theirs. Rows are keyed by what they show, so
 * what's open and selected carries over a reload.
 */

/** A list that may not have loaded yet, or failed to. */
sealed interface Loadable<out T> {
    data object NotLoaded : Loadable<Nothing>

    data object Loading : Loadable<Nothing>

    data class Loaded<T>(val value: T) : Loadable<T>

    data class Failed(val message: String) : Loadable<Nothing>
}

/** What the settings tree shows, for the project selected (if any). */
data class SettingsState(
    val projectSlug: String? = null,
    val projectEnvVars: Loadable<List<EnvVar>> = Loadable.NotLoaded,
    val contexts: Loadable<List<Context>> = Loadable.NotLoaded,
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

/** A row of the settings tree. */
sealed interface SettingsNode {
    val key: String
    val label: String

    /** A heading, above the settings it covers; it doesn't open or close. */
    data class Group(override val key: String, override val label: String) : SettingsNode

    /** A project's or context's environment variables, listed underneath. */
    data class EnvVars(val owner: EnvVarOwner, override val label: String) : SettingsNode {
        override val key = owner.key
    }

    /** The organization's contexts, listed underneath. */
    data object Contexts : SettingsNode {
        override val key = CONTEXTS_KEY
        override val label = "Contexts"
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
}

internal const val PROJECT_GROUP_KEY = "group:project"
internal const val ORG_GROUP_KEY = "group:org"
internal const val PROJECT_ENV_VARS_KEY = "project-env-vars"
internal const val CONTEXTS_KEY = "contexts"

/** The key of a context's node, which lists its environment variables. */
internal fun contextKey(contextId: String): String = "context:$contextId"

/** The tree to draw for [state]. With no project selected, there's only a message. */
internal fun settingsTree(state: SettingsState): Tree<SettingsNode> =
    buildTree {
        val slug = state.projectSlug
        if (slug == null) {
            addLeaf(SettingsNode.Message("no-project", "No CircleCI project selected"), "no-project")
            return@buildTree
        }
        addLeaf(SettingsNode.Group(PROJECT_GROUP_KEY, "Project settings"), PROJECT_GROUP_KEY)
        addEnvVars(EnvVarOwner.Project(slug), "Environment variables", state.projectEnvVars)
        addLeaf(SettingsNode.Group(ORG_GROUP_KEY, "Org settings"), ORG_GROUP_KEY)
        addNode(SettingsNode.Contexts, CONTEXTS_KEY) {
            addList(CONTEXTS_KEY, state.contexts, "No contexts") { context ->
                val owner = EnvVarOwner.OrgContext(context)
                addEnvVars(owner, context.name, state.contextEnvVars[context.id] ?: Loadable.NotLoaded)
            }
        }
    }

private fun TreeGeneratorScope<SettingsNode>.addEnvVars(
    owner: EnvVarOwner,
    label: String,
    vars: Loadable<List<EnvVar>>,
) {
    addNode(SettingsNode.EnvVars(owner, label), owner.key) {
        addList(owner.key, vars, "No environment variables") { envVar ->
            val node = SettingsNode.Variable(owner, envVar)
            addLeaf(node, node.key)
        }
    }
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
