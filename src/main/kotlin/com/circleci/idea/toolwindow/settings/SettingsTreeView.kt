// Jewel's speed search, which the whole view is built around, is experimental.
@file:OptIn(ExperimentalJewelApi::class)

package com.circleci.idea.toolwindow.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circleci.idea.api.models.Context
import com.circleci.idea.context.ContextPages
import com.circleci.idea.context.ContextRef
import com.circleci.idea.job.Placeholder
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.IndeterminateHorizontalProgressBar
import org.jetbrains.jewel.ui.component.SpeedSearchArea
import org.jetbrains.jewel.ui.component.SpeedSearchState
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.component.rememberSpeedSearchState
import org.jetbrains.jewel.ui.component.search.SpeedSearchableTree
import org.jetbrains.jewel.ui.component.search.highlightTextSearch
import org.jetbrains.jewel.ui.typography
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.Icon
import javax.swing.JComponent

/** Which settings a section shows, under its title. */
enum class SettingsSection(val title: String) {
    /** The selected project's environment variables. */
    PROJECT("Project Secrets"),

    /** The contexts of the project's organization, with theirs. */
    ORG("Org Secrets"),
}

/**
 * A settings section: its title and toolbar above its tree, drawn with
 * Jewel, with a right-click menu for each row.
 * Double-clicking a variable updates it.
 *
 * Its actions take the IDE's shortcuts for the same things (New, Delete,
 * Copy, Refresh), and Find opens the tree's speed search, as typing does.
 */
class SettingsTreeView(
    private val project: Project,
    private val model: SettingsTreeModel,
    private val scope: CoroutineScope,
    private val section: SettingsSection,
) {
    private val tree = if (section == SettingsSection.PROJECT) model.projectTree else model.orgTree

    // The row the actions act on: the one selected, or right-clicked.
    private var selected: SettingsNode? = null
    private var searchState: SpeedSearchState? = null

    // The project's variables are added to from anywhere in its section, a context's from its rows.
    private val addAction =
        when (section) {
            SettingsSection.PROJECT ->
                settingsAction("Add Environment Variable…", AllIcons.General.Add, IdeActions.ACTION_NEW_ELEMENT) {
                    model.state.value.projectSlug?.let(EnvVarOwner::Project)
                }
            SettingsSection.ORG ->
                settingsAction("Add Environment Variable…", AllIcons.General.Add, shortcutFrom = null) { node ->
                    (node as? SettingsNode.EnvVars)?.owner ?: (node as? SettingsNode.Variable)?.owner
                }
        }.performing { addVariable(it) }

    private val openContextAction =
        settingsAction("Open Context", AllIcons.Actions.EditSource, IdeActions.ACTION_EDIT_SOURCE) { node ->
            val context =
                (node as? SettingsNode.EnvVars)?.owner?.context
                    ?: ((node as? SettingsNode.Variable)?.owner as? EnvVarOwner.OrgContext)?.context
            context?.let { model.state.value.projectSlug?.let { slug -> ContextRef(it.id, it.name, slug) } }
        }.performing { ContextPages.getInstance(project).open(it) }

    private val newContextAction =
        settingsAction("New Context…", AllIcons.General.Add, IdeActions.ACTION_NEW_ELEMENT) {
            model.state.value.projectSlug
        }.performing { newContext(it) }

    private val updateAction =
        settingsAction("Update Value…", AllIcons.Actions.Edit, shortcutFrom = null) { it as? SettingsNode.Variable }
            .performing { updateVariable(it) }

    // A variable, or in the organization's section, a context.
    private val deleteAction =
        settingsAction("Delete…", AllIcons.General.Remove, IdeActions.ACTION_DELETE) { node ->
            node.takeIf { it is SettingsNode.Variable || it is SettingsNode.EnvVars }
        }.performing { node ->
            when (node) {
                is SettingsNode.Variable -> deleteVariable(node)
                is SettingsNode.EnvVars -> deleteContext(node.owner.context)
                else -> Unit
            }
        }

    private val copyNameAction =
        settingsAction("Copy Name", AllIcons.Actions.Copy, IdeActions.ACTION_COPY) { it as? SettingsNode.Variable }
            .performing { copy(it.envVar.name) }

    private val refreshAction =
        settingsAction("Refresh", AllIcons.Actions.Refresh, IdeActions.ACTION_REFRESH) {
            model.state.value.projectSlug
        }.performing {
            when (section) {
                SettingsSection.PROJECT -> model.refreshEnvVars(EnvVarOwner.Project(it))
                SettingsSection.ORG -> model.refreshContexts()
            }
        }

    // The full settings, in the web app: the project's, or its organization's.
    private val openInBrowserAction =
        when (section) {
            SettingsSection.PROJECT ->
                settingsAction("Open Project Settings in Browser", AllIcons.Ide.External_link_arrow, null) {
                    model.state.value.projectSlug?.let(SettingsWebUrls::project)
                }
            SettingsSection.ORG ->
                settingsAction("Open Org Settings in Browser", AllIcons.Ide.External_link_arrow, null) {
                    model.state.value.projectSlug?.let(SettingsWebUrls::organization)
                }
        }.performing { BrowserUtil.browse(it) }

    private val findAction =
        settingsAction("Find", AllIcons.Actions.Find, IdeActions.ACTION_FIND) { Unit }
            .performing { searchState?.isVisible = true }

    private val toolbarActions =
        when (section) {
            SettingsSection.PROJECT -> listOf(addAction, refreshAction, openInBrowserAction)
            SettingsSection.ORG -> listOf(newContextAction, openContextAction, refreshAction, openInBrowserAction)
        }

    private val actions =
        (toolbarActions + listOf(addAction, updateAction, deleteAction, copyNameAction, findAction, openContextAction))
            .distinct()

    /** The title and toolbar above the tree, with the actions' shortcuts working while the tree has focus. */
    fun component(): JComponent {
        val treePanel = JewelComposePanel { View() }
        actions.forEach { it.registerCustomShortcutSet(it.shortcutSet, treePanel) }
        val toolbar =
            ActionManager.getInstance()
                .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, DefaultActionGroup(toolbarActions), true)
        toolbar.targetComponent = treePanel
        val header =
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = JBUI.Borders.emptyLeft(TITLE_PADDING)
                add(JBLabel(section.title).apply { font = JBFont.label().asBold() }, BorderLayout.WEST)
                add(toolbar.component, BorderLayout.EAST)
            }
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(treePanel, BorderLayout.CENTER)
        }
    }

    @Composable
    private fun View() {
        val state by model.state.collectAsState()
        val nodes =
            remember(state) {
                when (section) {
                    SettingsSection.PROJECT -> projectSettingsTree(state)
                    SettingsSection.ORG -> orgSettingsTree(state)
                }
            }
        val loads by tree.loads.collectAsState()
        val search = rememberSpeedSearchState()
        SideEffect { searchState = search }
        Box(Modifier.fillMaxSize()) {
            // A placeholder while the list first loads; a context shows its own loading row.
            val placeholder = placeholder(state)
            if (placeholder != null) {
                Placeholder(placeholder)
            } else {
                SpeedSearchArea(search, Modifier.fillMaxSize()) {
                    val area = this
                    VerticallyScrollableContainer(tree.scroll, Modifier.fillMaxSize()) {
                        area.SpeedSearchableTree(
                            tree = nodes,
                            nodeText = { it.data.searchText() },
                            modifier = Modifier.fillMaxSize().focusable(),
                            treeState = tree.treeState,
                            onElementClick = { element ->
                                if (element.data is SettingsNode.MoreContexts) model.loadMoreContexts()
                            },
                            onElementDoubleClick = { element ->
                                (element.data as? SettingsNode.Variable)?.let(::updateVariable)
                            },
                            onSelectionChange = { elements -> selected = elements.firstOrNull()?.data },
                        ) { element ->
                            val node = element.data
                            Box(Modifier.fillMaxWidth().rowPointer(node)) {
                                SettingsRow(node, isSelected && isActive)
                            }
                        }
                    }
                }
            }
            if (loads > 0) IndeterminateHorizontalProgressBar(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    /** What to show in place of the section's list while it first loads, if it is. */
    private fun placeholder(state: SettingsState): String? {
        if (state.projectSlug == null) return null
        return when (section) {
            SettingsSection.PROJECT ->
                "Loading environment variables...".takeIf { state.projectEnvVars.isLoading() }
            SettingsSection.ORG -> "Loading contexts...".takeIf { state.contexts.isLoading() }
        }
    }

    /**
     * Pressing a row ends the speed search, as in the IDE's own trees. While
     * it's open, Jewel's tree puts the selection back on a matching row, so a
     * row that doesn't match, such as a context's variable, couldn't be
     * selected. The row sees the press before the tree selects it.
     *
     * Right-clicking a row selects it and offers its actions.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun Modifier.rowPointer(node: SettingsNode): Modifier =
        onPointerEvent(PointerEventType.Press) {
            searchState?.isVisible = false
            showPopupMenu(node, it)
        }
            // Platforms differ on whether the press or the release is the popup trigger.
            .onPointerEvent(PointerEventType.Release) { showPopupMenu(node, it) }

    private fun showPopupMenu(
        node: SettingsNode,
        event: PointerEvent,
    ) {
        val mouse = event.awtEventOrNull?.takeIf { it.isPopupTrigger } ?: return
        tree.treeState.selectedKeys = setOf(node.key)
        selected = node
        val group =
            DefaultActionGroup().apply {
                if (section == SettingsSection.ORG) {
                    add(openContextAction)
                    addSeparator()
                    add(newContextAction)
                }
                add(addAction)
                add(updateAction)
                add(deleteAction)
                addSeparator()
                add(copyNameAction)
                addSeparator()
                add(refreshAction)
            }
        ActionManager.getInstance().createActionPopupMenu(ActionPlaces.TOOLWINDOW_POPUP, group)
            .component.show(mouse.component, mouse.x, mouse.y)
    }

    private fun addVariable(owner: EnvVarOwner) {
        val dialog = EnvVarDialog(project, existingName = null, ownerLabel = owner.label())
        if (!dialog.showAndGet()) return
        change("Couldn't add ${dialog.name}") { model.setEnvVar(owner, dialog.name, dialog.value) }
    }

    private fun updateVariable(node: SettingsNode.Variable) {
        val name = node.envVar.name
        val dialog = EnvVarDialog(project, existingName = name, ownerLabel = node.owner.label())
        if (!dialog.showAndGet()) return
        change("Couldn't update $name") { model.setEnvVar(node.owner, name, dialog.value) }
    }

    private fun newContext(projectSlug: String) {
        val dialog = NewContextDialog(project, projectSlug)
        if (!dialog.showAndGet()) return
        // The new context opens on its page, to restrict it and add its variables.
        change("Couldn't create ${dialog.name}") {
            model.createContext(
                dialog.name,
            ).map { ContextPages.getInstance(project).open(ContextRef(it.id, it.name, projectSlug)) }
        }
    }

    private fun deleteContext(context: Context) {
        val confirmed =
            MessageDialogBuilder.yesNo(
                "Delete the context ${context.name}?",
                "Its environment variables and restrictions are deleted with it, and jobs using it will fail.",
            )
                .yesText("Delete")
                .noText("Cancel")
                .asWarning()
                .ask(project)
        if (!confirmed) return
        change("Couldn't delete ${context.name}") { model.deleteContext(context) }
    }

    private fun deleteVariable(node: SettingsNode.Variable) {
        val name = node.envVar.name
        val confirmed =
            MessageDialogBuilder.yesNo("Delete $name?", "Builds using ${node.owner.label()} will no longer get it.")
                .yesText("Delete")
                .noText("Cancel")
                .asWarning()
                .ask(project)
        if (!confirmed) return
        change("Couldn't delete $name") { model.deleteEnvVar(node.owner, name) }
    }

    private fun change(
        failureTitle: String,
        call: suspend () -> Result<Unit>,
    ) {
        scope.launch {
            call().onFailure { Messages.showErrorDialog(project, it.message ?: "Request failed", failureTitle) }
        }
    }

    private fun copy(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    /** An action on the selected row; see [SelectionAction]. */
    private fun <T : Any> settingsAction(
        text: String,
        icon: Icon,
        shortcutFrom: String?,
        target: (SettingsNode?) -> T?,
    ): SelectionAction<SettingsNode, T> =
        SelectionAction(text, icon, shortcutFrom, { selected }, { searchState?.isVisible == true }, target)
}

/** What to call where an environment variable is kept, in a sentence. */
private fun EnvVarOwner.label(): String =
    when (this) {
        is EnvVarOwner.Project -> "the project $slug"
        is EnvVarOwner.OrgContext -> "the context ${context.name}"
    }

private fun Loadable<*>.isLoading(): Boolean = this == Loadable.NotLoaded || this == Loadable.Loading

/** What speed search matches a row by: its name, for the rows that have one. */
private fun SettingsNode.searchText(): String =
    when (this) {
        is SettingsNode.Message, is SettingsNode.MoreContexts -> ""
        else -> label
    }

/** A row: its label, and for a variable, its masked value in grey after it. */
@Composable
private fun SettingsRow(
    node: SettingsNode,
    onSelection: Boolean,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GAP.dp),
    ) {
        val isMessage = node is SettingsNode.Message || node is SettingsNode.MoreContexts
        val isError =
            when (node) {
                is SettingsNode.Message -> node.error
                is SettingsNode.MoreContexts -> node.error != null
                else -> false
            }
        val style =
            JewelTheme.defaultTextStyle.let {
                if (isMessage && !isError) it.copy(fontStyle = FontStyle.Italic) else it
            }
        val color =
            when {
                isError -> JewelTheme.globalColors.text.error
                isMessage -> detailColor(onSelection)
                else -> Color.Unspecified
            }
        Text(
            // What speed search matched, highlighted.
            node.label.highlightTextSearch(),
            Modifier.weight(1f, fill = false),
            color = color,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (node is SettingsNode.Variable && node.envVar.maskedValue.isNotEmpty()) {
            Text(
                node.envVar.maskedValue,
                Modifier.padding(start = GAP.dp),
                color = detailColor(onSelection),
                style = JewelTheme.typography.small,
                maxLines = 1,
            )
        }
    }
}

/** Grey, unless on the focused selection's background, where it wouldn't read. */
@Composable
private fun detailColor(onSelection: Boolean): Color =
    if (onSelection) Color.Unspecified else JewelTheme.globalColors.text.info

private const val GAP = 6
private const val TITLE_PADDING = 8
