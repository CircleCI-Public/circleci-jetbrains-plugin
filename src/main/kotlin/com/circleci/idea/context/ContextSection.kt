// Jewel's speed search, which the section's list is built around, is experimental.
@file:OptIn(ExperimentalJewelApi::class)

package com.circleci.idea.context

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circleci.idea.job.Placeholder
import com.circleci.idea.job.rowBackground
import com.circleci.idea.toolwindow.settings.Loadable
import com.circleci.idea.toolwindow.settings.SelectionAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.ContextHelpLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.SelectionMode
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.SpeedSearchArea
import org.jetbrains.jewel.ui.component.SpeedSearchState
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.component.rememberSpeedSearchState
import org.jetbrains.jewel.ui.component.search.SpeedSearchableLazyColumn
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import java.net.URI
import javax.swing.Icon
import javax.swing.JComponent

/**
 * What a section is for, shown from the help icon by its title, with a link
 * to the docs if it has some.
 */
data class SectionHelp(
    val text: String,
    val docsText: String? = null,
    val docsUrl: String? = null,
)

/** A link in an empty section's list, to add its first item: the section's own add action. */
data class EmptyLink(
    val text: String,
    val perform: () -> Unit,
)

/**
 * One of a context page's sections: a titled list, drawn with Jewel, with
 * the actions on its rows in a toolbar above it and a right-click menu.
 *
 * @param items The section's list, from the page's state
 * @param text What speed search matches a row by, and Copy copies
 * @param header The list's column headings, if it has columns
 * @param row A row's content, told whether it's on the focused selection
 */
@Suppress("LongParameterList")
class ContextSection<T : Any>(
    private val title: String,
    private val help: SectionHelp,
    private val items: StateFlow<Loadable<List<T>>>,
    private val emptyText: String,
    private val key: (T) -> Any,
    private val text: (T) -> String,
    private val header: (@Composable () -> Unit)? = null,
    private val row: @Composable RowScope.(item: T, onSelection: Boolean) -> Unit,
) {
    private val listState = SelectableLazyListState(LazyListState(), SelectionMode.Single)
    private var searchState: SpeedSearchState? = null

    private var toolbarActions: List<AnAction> = emptyList()
    private var popupActions: List<AnAction> = emptyList()
    private var onDoubleClick: (T) -> Unit = {}
    private var emptyLink: EmptyLink? = null
    private var shortcutActions: List<AnAction> = emptyList()

    /** The row selected, if it's still listed. */
    val selected: T?
        get() {
            val keys = listState.selectedKeys
            return (items.value as? Loadable.Loaded)?.value?.firstOrNull { key(it) in keys }
        }

    /** An action on the selected row, or on none if [target] doesn't need one; see [SelectionAction]. */
    fun <R : Any> action(
        text: String,
        icon: Icon,
        shortcutFrom: String?,
        target: (T?) -> R?,
    ): SelectionAction<T, R> {
        val searching = { searchState?.isVisible == true }
        return SelectionAction(text, icon, shortcutFrom, { selected }, searching, target)
    }

    /**
     * Show [toolbar] in the header, and [popup] in a row's right-click
     * menu (null for a separator), followed by Copy; double-clicking a row
     * does [onDoubleClick], and an empty list shows [emptyLink].
     */
    fun actions(
        toolbar: List<AnAction>,
        popup: List<AnAction?>,
        emptyLink: EmptyLink,
        onDoubleClick: (T) -> Unit = {},
    ) {
        this.emptyLink = emptyLink
        val copy = action("Copy", AllIcons.Actions.Copy, IdeActions.ACTION_COPY) { it }.performing { copy(text(it)) }
        val find =
            action("Find", AllIcons.Actions.Find, IdeActions.ACTION_FIND) { Unit }
                .performing { searchState?.isVisible = true }
        toolbarActions = toolbar
        popupActions = popup.map { it ?: Separator.getInstance() } + listOf(Separator.getInstance(), copy)
        this.onDoubleClick = onDoubleClick
        shortcutActions = (toolbar + popup.filterNotNull() + listOf(copy, find)).distinct()
    }

    /** The list, once [component] has made it. */
    lateinit var list: JComponent
        private set

    /** The title, help and toolbar above the list, with the actions' shortcuts working while the list has focus. */
    fun component(): JComponent {
        list = JewelComposePanel { View() }
        shortcutActions.forEach { it.registerCustomShortcutSet(it.shortcutSet, list) }
        val toolbar =
            ActionManager.getInstance()
                .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, DefaultActionGroup(toolbarActions), true)
        toolbar.targetComponent = list
        val top =
            panel {
                row {
                    label(title).bold().gap(RightGap.SMALL)
                    cell(helpIcon()).resizableColumn()
                    cell(toolbar.component).align(AlignX.RIGHT)
                }
            }.apply { border = JBUI.Borders.empty(0, SIDE_PADDING) }
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(list, BorderLayout.CENTER)
        }
    }

    private fun helpIcon(): JComponent {
        val url = help.docsUrl ?: return ContextHelpLabel.create(help.text)
        return ContextHelpLabel.createWithBrowserLink(
            null,
            help.text,
            help.docsText ?: "Documentation",
            URI(url).toURL(),
        )
    }

    @Composable
    private fun View() {
        val loadable by items.collectAsState()
        val search = rememberSpeedSearchState()
        SideEffect { searchState = search }
        when (val current = loadable) {
            Loadable.NotLoaded, Loadable.Loading -> Placeholder("Loading...")
            is Loadable.Failed -> Placeholder(current.message)
            is Loadable.Loaded ->
                if (current.value.isEmpty()) {
                    EmptyList(emptyText, emptyLink)
                } else {
                    Column(Modifier.fillMaxSize()) {
                        header?.let {
                            it()
                            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                        }
                        Rows(current.value, search)
                    }
                }
        }
    }

    @Composable
    private fun Rows(
        list: List<T>,
        search: SpeedSearchState,
    ) {
        SpeedSearchArea(search, Modifier.fillMaxSize()) {
            val area = this
            VerticallyScrollableContainer(listState.lazyListState, Modifier.fillMaxSize()) {
                area.SpeedSearchableLazyColumn(
                    modifier = Modifier.fillMaxSize().focusable(),
                    selectionMode = SelectionMode.Single,
                    state = listState,
                ) {
                    items(list, textContent = text, key = key) { item ->
                        Box(Modifier.fillMaxWidth().rowBackground(isSelected, isActive).rowPointer(item)) {
                            ContextRow { row(item, isSelected && isActive) }
                        }
                    }
                }
            }
        }
    }

    /**
     * Pressing a row ends the speed search, as in the IDE's own lists;
     * right-clicking it selects it and offers its actions; double-clicking
     * it does what the section double-clicks do.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun Modifier.rowPointer(item: T): Modifier =
        onPointerEvent(PointerEventType.Press) { event ->
            searchState?.isVisible = false
            val mouse = event.awtEventOrNull ?: return@onPointerEvent
            if (mouse.clickCount == 2 && !mouse.isPopupTrigger) onDoubleClick(item)
            showPopupMenu(item, mouse)
        }
            // Platforms differ on whether the press or the release is the popup trigger.
            .onPointerEvent(PointerEventType.Release) { event -> event.awtEventOrNull?.let { showPopupMenu(item, it) } }

    private fun showPopupMenu(
        item: T,
        mouse: MouseEvent,
    ) {
        if (!mouse.isPopupTrigger) return
        listState.selectedKeys = setOf(key(item))
        ActionManager.getInstance()
            .createActionPopupMenu(ActionPlaces.POPUP, DefaultActionGroup(popupActions))
            .component.show(mouse.component, mouse.x, mouse.y)
    }

    private fun copy(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private companion object {
        const val SIDE_PADDING = 8
    }
}

/** What an empty list shows: that it's empty, and a link to add its first item. */
@Composable
private fun EmptyList(
    text: String,
    link: EmptyLink?,
) {
    Column(
        Modifier.fillMaxSize().padding(EMPTY_PADDING.dp),
        verticalArrangement = Arrangement.spacedBy(EMPTY_GAP.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = JewelTheme.globalColors.text.info, textAlign = TextAlign.Center)
        link?.let { Link(it.text, it.perform) }
    }
}

/** A row of a section's list, or its column headings: cells side by side, as a table lays them out. */
@Composable
internal fun ContextRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().height(ROW_HEIGHT.dp).padding(horizontal = ROW_PADDING.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CELL_GAP.dp),
        content = content,
    )
}

private const val ROW_HEIGHT = 24
private const val ROW_PADDING = 8
private const val CELL_GAP = 12
private const val EMPTY_PADDING = 16
private const val EMPTY_GAP = 6
