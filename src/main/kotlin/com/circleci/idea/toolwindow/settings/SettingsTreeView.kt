// Jewel's speed search, which the whole view is built around, is experimental.
@file:OptIn(ExperimentalJewelApi::class)

package com.circleci.idea.toolwindow.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.SpeedSearchArea
import org.jetbrains.jewel.ui.component.SpeedSearchState
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.component.rememberSpeedSearchState
import org.jetbrains.jewel.ui.component.search.SpeedSearchableTree
import org.jetbrains.jewel.ui.component.search.highlightTextSearch
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography
import java.awt.datatransfer.StringSelection
import javax.swing.Icon
import javax.swing.JComponent

/**
 * The settings tree, drawn with Jewel, with a right-click menu for each row.
 * Double-clicking a variable updates it.
 *
 * Its actions take the IDE's shortcuts for the same things (New, Delete,
 * Copy, Refresh), and Find opens the tree's speed search, as typing does.
 */
class SettingsTreeView(
    private val project: Project,
    private val model: SettingsTreeModel,
    private val scope: CoroutineScope,
) {
    // The row the actions act on: the one selected, or right-clicked.
    private var selected: SettingsNode? = null
    private var searchState: SpeedSearchState? = null

    private val addAction =
        SettingsAction("Add Environment Variable…", AllIcons.General.Add, IdeActions.ACTION_NEW_ELEMENT) { node ->
            (node as? SettingsNode.EnvVars)?.owner ?: (node as? SettingsNode.Variable)?.owner
        }.performing { addVariable(it) }

    private val updateAction =
        SettingsAction("Update Value…", AllIcons.Actions.Edit, shortcutFrom = null) { it as? SettingsNode.Variable }
            .performing { updateVariable(it) }

    private val deleteAction =
        SettingsAction("Delete…", AllIcons.General.Remove, IdeActions.ACTION_DELETE) { it as? SettingsNode.Variable }
            .performing { deleteVariable(it) }

    private val copyNameAction =
        SettingsAction("Copy Name", AllIcons.Actions.Copy, IdeActions.ACTION_COPY) { it as? SettingsNode.Variable }
            .performing { copy(it.envVar.name) }

    private val refreshAction =
        SettingsAction("Refresh", AllIcons.Actions.Refresh, IdeActions.ACTION_REFRESH) { Unit }
            .performing { model.refresh() }

    private val findAction =
        SettingsAction("Find", AllIcons.Actions.Find, IdeActions.ACTION_FIND) { Unit }
            .performing { searchState?.isVisible = true }

    private val actions = listOf(addAction, updateAction, deleteAction, copyNameAction, refreshAction, findAction)

    /** The tree in a Swing panel, with the actions' shortcuts working while it has focus. */
    fun component(): JComponent =
        JewelComposePanel { View() }.also { panel ->
            actions.forEach { it.registerCustomShortcutSet(it.shortcutSet, panel) }
        }

    @Composable
    private fun View() {
        val state by model.state.collectAsState()
        val tree = remember(state) { settingsTree(state) }
        val search = rememberSpeedSearchState()
        SideEffect { searchState = search }
        Column(Modifier.fillMaxSize()) {
            Text("Settings", Modifier.padding(TITLE_PADDING.dp), style = JewelTheme.typography.h4TextStyle)
            SpeedSearchArea(search, Modifier.fillMaxSize()) {
                val area = this
                VerticallyScrollableContainer(model.scroll, Modifier.fillMaxSize()) {
                    area.SpeedSearchableTree(
                        tree = tree,
                        nodeText = { it.data.searchText() },
                        modifier = Modifier.fillMaxSize().focusable(),
                        treeState = model.treeState,
                        onElementDoubleClick = { element ->
                            (element.data as? SettingsNode.Variable)?.let(::updateVariable)
                        },
                        onSelectionChange = { elements -> selected = elements.firstOrNull()?.data },
                    ) { element ->
                        val node = element.data
                        Box(Modifier.fillMaxWidth().popupMenu(node)) {
                            SettingsRow(node, isSelected && isActive, refreshFor(node))
                        }
                    }
                }
            }
        }
    }

    /** Right-clicking a row selects it and offers its actions. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun Modifier.popupMenu(node: SettingsNode): Modifier =
        // Platforms differ on whether the press or the release is the popup trigger.
        onPointerEvent(PointerEventType.Press) { showPopupMenu(node, it) }
            .onPointerEvent(PointerEventType.Release) { showPopupMenu(node, it) }

    private fun showPopupMenu(
        node: SettingsNode,
        event: PointerEvent,
    ) {
        val mouse = event.awtEventOrNull?.takeIf { it.isPopupTrigger } ?: return
        model.treeState.selectedKeys = setOf(node.key)
        selected = node
        val group =
            DefaultActionGroup().apply {
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

    /** Reloading the project's variables or the contexts, from a button on their row. */
    private fun refreshFor(node: SettingsNode): (() -> Unit)? =
        when (node) {
            is SettingsNode.EnvVars -> if (node.owner is EnvVarOwner.Project) model::refreshProjectEnvVars else null
            is SettingsNode.Contexts -> model::refreshContexts
            else -> null
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

    /**
     * An action on the selected row, shown and enabled only where [target]
     * finds something in it to act on, with the shortcut of the IDE's own
     * [shortcutFrom] action. It's off while the speed search is open, so the
     * search field gets the keys.
     */
    private inner class SettingsAction<T : Any>(
        text: String,
        icon: Icon,
        shortcutFrom: String?,
        private val target: (SettingsNode?) -> T?,
    ) : DumbAwareAction(text, null, icon) {
        private var perform: (T) -> Unit = {}

        init {
            shortcutFrom?.let { ActionManager.getInstance().getAction(it) }?.let(::copyShortcutFrom)
        }

        fun performing(perform: (T) -> Unit): SettingsAction<T> = apply { this.perform = perform }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = searchState?.isVisible != true && target(selected) != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            target(selected)?.let(perform)
        }
    }
}

/** What to call where an environment variable is kept, in a sentence. */
private fun EnvVarOwner.label(): String =
    when (this) {
        is EnvVarOwner.Project -> "the project $slug"
        is EnvVarOwner.OrgContext -> "the context ${context.name}"
    }

/** What speed search matches a row by: its name, for the rows that have one. */
private fun SettingsNode.searchText(): String =
    when (this) {
        is SettingsNode.Group, is SettingsNode.Message -> ""
        else -> label
    }

/**
 * A row: a heading, or its label, and for a variable, its masked value in
 * grey after it. With [onRefresh], a refresh button at its right edge.
 */
@Composable
private fun SettingsRow(
    node: SettingsNode,
    onSelection: Boolean,
    onRefresh: (() -> Unit)?,
) {
    if (node is SettingsNode.Group) {
        GroupHeader(node.label, Modifier.fillMaxWidth())
        return
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GAP.dp),
    ) {
        val message = node as? SettingsNode.Message
        val style =
            JewelTheme.defaultTextStyle.let {
                if (message != null && !message.error) it.copy(fontStyle = FontStyle.Italic) else it
            }
        val color =
            when {
                message?.error == true -> JewelTheme.globalColors.text.error
                message != null -> detailColor(onSelection)
                else -> Color.Unspecified
            }
        Text(
            // What speed search matched, highlighted.
            node.label.highlightTextSearch(),
            // Filling the row, when there's a button, puts it at the right edge.
            Modifier.weight(1f, fill = onRefresh != null),
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
        onRefresh?.let { RefreshButton(it) }
    }
}

@OptIn(ExperimentalFoundationApi::class) // Jewel's tooltips are built on TooltipArea
@Composable
private fun RefreshButton(onClick: () -> Unit) {
    IconActionButton(AllIconsKeys.Actions.Refresh, "Refresh", onClick) { Text("Refresh") }
}

/** Grey, unless on the focused selection's background, where it wouldn't read. */
@Composable
private fun detailColor(onSelection: Boolean): Color =
    if (onSelection) Color.Unspecified else JewelTheme.globalColors.text.info

private const val GAP = 6
private const val TITLE_PADDING = 8
