package com.circleci.idea.context

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.circleci.idea.api.models.ContextRestriction
import com.circleci.idea.api.models.EnvVar
import com.circleci.idea.api.models.NamedEntity
import com.circleci.idea.api.models.RestrictionType
import com.circleci.idea.toolwindow.settings.EnvVarDialog
import com.circleci.idea.toolwindow.settings.Loadable
import com.circleci.idea.toolwindow.settings.SettingsWebUrls
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.typography
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.time.Instant
import javax.swing.Icon
import javax.swing.JComponent

/**
 * A context's page, modelled on the web app's: its environment variables
 * and its restrictions. Standalone organizations can't restrict contexts to
 * groups, so their pages leave that section out.
 */
class ContextPanel(
    private val project: Project,
    private val ref: ContextRef,
) : JBPanel<ContextPanel>(BorderLayout()), Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val model = ContextPageModel(project, ref, scope)

    private val envVars =
        ContextSection(
            title = "Environment Variables",
            help = ENV_VAR_HELP,
            items = state { it.envVars },
            emptyText = "No environment variables",
            key = EnvVar::name,
            text = EnvVar::name,
            header = { EnvVarHeader() },
        ) { envVar, onSelection -> EnvVarCells(envVar, onSelection) }

    private val groups =
        restrictionSection(
            RestrictionType.GROUP,
            "Group Restrictions",
            SectionHelp("Only members of the groups listed can run jobs that use this context."),
            "No group restrictions: the context can't be used until it has one",
        )

    private val projects =
        restrictionSection(
            RestrictionType.PROJECT,
            "Project Restrictions",
            SectionHelp(
                "Only the projects listed can use this context, so only trusted projects get its secrets. " +
                    "A context with no project restrictions is available to every project.",
            ),
            "No project restrictions: every project can use the context",
        )

    private val expressions =
        restrictionSection(
            RestrictionType.EXPRESSION,
            "Expression Restrictions",
            SectionHelp(
                "Only pipelines whose values match the expressions listed can use this context.",
                "Expression restrictions",
                EXPRESSION_DOCS,
            ),
            "No expression restrictions",
            monospace = true,
        )

    private val refreshAction =
        pageAction("Refresh", AllIcons.Actions.Refresh, IdeActions.ACTION_REFRESH) { model.refresh() }

    private val copyIdAction = pageAction("Copy Context ID", AllIcons.Actions.Copy, null) { copy(ref.id) }

    private val deleteAction =
        pageAction("Delete Context…", AllIcons.General.Remove, null) {
            if (confirm(project, "Delete the context ${ref.name}?", DELETE_CONTEXT_WARNING, "Delete")) {
                change("Couldn't delete ${ref.name}") { model.deleteContext() }
            }
        }

    private val openInBrowserAction =
        pageAction("Open in Browser", AllIcons.Ide.External_link_arrow, null) {
            SettingsWebUrls.context(ref.projectSlug, ref.id)?.let(BrowserUtil::browse)
        }

    private val loadError = JBLabel().apply { foreground = NamedColorUtil.getErrorForeground() }
    private val loading = AsyncProcessIcon("Loading context")

    val preferredFocusedComponent: JComponent
        get() = envVars.list

    init {
        Disposer.register(this, loading)
        configureEnvVars()
        configureRestrictions()

        val toolbar =
            ActionManager.getInstance().createActionToolbar(
                ActionPlaces.TOOLBAR,
                DefaultActionGroup(
                    refreshAction,
                    copyIdAction,
                    openInBrowserAction,
                    Separator.getInstance(),
                    deleteAction,
                ),
                true,
            )
        toolbar.targetComponent = this
        refreshAction.registerCustomShortcutSet(refreshAction.shortcutSet, this)

        val sections = listOfNotNull(envVars, groups.takeUnless { ref.isStandaloneOrg }, projects, expressions)
        // Beside the toolbar: that the page is loading, or why the context didn't load.
        val status =
            JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEADING, JBUI.scale(STATUS_GAP), 0)).apply {
                isOpaque = false
                add(loading)
                add(loadError)
            }
        add(
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(toolbar.component, BorderLayout.WEST)
                add(status, BorderLayout.CENTER)
            },
            BorderLayout.NORTH,
        )
        add(stack(sections.map { it.component() }, listOf(2f) + List(sections.size - 1) { 1f }), BorderLayout.CENTER)

        scope.launch {
            model.state.collect { state ->
                loadError.text = (state.context as? Loadable.Failed)?.let { "Couldn't load the context: ${it.message}" }
                loadError.isVisible = loadError.text != null
            }
        }
        scope.launch {
            model.loads.collect { loads ->
                loading.isVisible = loads > 0
                if (loads > 0) loading.resume() else loading.suspend()
            }
        }
    }

    private fun configureEnvVars() {
        val owner = "the context ${ref.name}"
        val addEnvVar = {
            val dialog = EnvVarDialog(project, existingName = null, ownerLabel = owner)
            if (dialog.showAndGet()) {
                change(
                    "Couldn't add ${dialog.name}",
                ) { model.setEnvVar(dialog.name, dialog.value) }
            }
        }
        val add =
            envVars.action("Add Environment Variable…", AllIcons.General.Add, IdeActions.ACTION_NEW_ELEMENT) { Unit }
                .performing { addEnvVar() }
        val update = envVars.action("Update Value…", AllIcons.Actions.Edit, null) { it }.performing(::updateEnvVar)
        val delete =
            envVars.action("Delete…", AllIcons.General.Remove, IdeActions.ACTION_DELETE) { it }
                .performing { envVar ->
                    val message = "Jobs using $owner will no longer get it."
                    if (confirm(project, "Delete ${envVar.name}?", message, "Delete")) {
                        change("Couldn't delete ${envVar.name}") { model.deleteEnvVar(envVar.name) }
                    }
                }
        envVars.actions(
            listOf(add, update, delete),
            listOf(add, update, delete),
            EmptyLink("Add an environment variable", addEnvVar),
            onDoubleClick = ::updateEnvVar,
        )
    }

    private fun updateEnvVar(envVar: EnvVar) {
        val dialog = EnvVarDialog(project, existingName = envVar.name, ownerLabel = "the context ${ref.name}")
        if (dialog.showAndGet()) change("Couldn't update ${envVar.name}") { model.setEnvVar(envVar.name, dialog.value) }
    }

    private fun configureRestrictions() {
        configure(groups, "Group Restriction", ::addGroup) {
            Confirmation("Remove the restriction to ${it.label}?", "Jobs that use the context may fail without it.")
        }
        configure(projects, "Project Restriction", ::addProject) {
            Confirmation(
                "Remove the restriction to ${it.label}?",
                "Without project restrictions, every project in the organization can use the context.",
            )
        }
        configure(expressions, "Expression Restriction", ::addExpression) {
            Confirmation("Remove this expression restriction?", it.restriction.value)
        }
    }

    private data class Confirmation(val title: String, val message: String)

    private fun configure(
        section: ContextSection<LabeledRestriction>,
        kind: String,
        add: () -> Unit,
        confirmation: (LabeledRestriction) -> Confirmation,
    ) {
        val addAction =
            section.action(
                "Add $kind…",
                AllIcons.General.Add,
                IdeActions.ACTION_NEW_ELEMENT,
            ) { Unit }.performing { add() }
        val remove =
            section.action("Remove…", AllIcons.General.Remove, IdeActions.ACTION_DELETE) { it }
                .performing { row ->
                    val (title, message) = confirmation(row)
                    if (confirm(project, title, message, "Remove")) {
                        change("Couldn't remove the restriction") { model.deleteRestriction(row.restriction) }
                    }
                }
        val article = if (kind.first() in "AEIOU") "an" else "a"
        section.actions(
            listOf(addAction, remove),
            listOf(addAction, remove),
            EmptyLink("Add $article ${kind.lowercase()}", add),
        )
    }

    private fun addGroup() {
        scope.launch {
            val orgId = model.orgId().getOrElse { return@launch showError(project, "Couldn't list the groups", it) }
            val groups = model.groups().getOrElse { return@launch showError(project, "Couldn't list the groups", it) }
            val restricted = restricted(RestrictionType.GROUP)
            val choices =
                (listOf(NamedEntity(orgId, ALL_MEMBERS)) + groups.sortedBy { it.name.lowercase() })
                    .filterNot { it.id in restricted }
            if (choices.isEmpty()) {
                Messages.showInfoMessage(
                    project,
                    "The context is restricted to every group already.",
                    "No Groups to Add",
                )
                return@launch
            }
            GroupRestrictionDialog(project, choices) { model.addRestriction(RestrictionType.GROUP, it) }.show()
        }
    }

    private fun addProject() {
        ProjectRestrictionDialog(
            project,
            restricted(RestrictionType.PROJECT),
            search = { model.searchProjects(it) },
        ) { model.addRestriction(RestrictionType.PROJECT, it) }.show()
    }

    private fun addExpression() {
        ExpressionRestrictionDialog(project) { model.addRestriction(RestrictionType.EXPRESSION, it) }.show()
    }

    /** The values of the restrictions of [type] the context has: what it's restricted to already. */
    private fun restricted(type: RestrictionType): Set<String> =
        (model.state.value.restrictions(type) as? Loadable.Loaded)?.value.orEmpty().mapTo(mutableSetOf()) { it.value }

    /** A section of the restrictions of [type], each by what it restricts the context to. */
    private fun restrictionSection(
        type: RestrictionType,
        title: String,
        help: SectionHelp,
        emptyText: String,
        monospace: Boolean = false,
    ): ContextSection<LabeledRestriction> =
        ContextSection(
            title = title,
            help = help,
            items = state { labeled(it, type) },
            emptyText = emptyText,
            key = { it.restriction.id },
            text = LabeledRestriction::label,
        ) { row, _ -> RestrictionCell(row, monospace) }

    /** The restrictions of [type], each with its label, by their labels. */
    private fun labeled(
        state: ContextPageState,
        type: RestrictionType,
    ): Loadable<List<LabeledRestriction>> {
        val orgId = (state.context as? Loadable.Loaded)?.value?.orgId
        return state.restrictions(type).map { list ->
            list.map { LabeledRestriction(it, label(it, orgId)) }.sortedBy { it.label.lowercase() }
        }
    }

    /** What's [select]ed from the page's state, as it changes. */
    private fun <T> state(select: (ContextPageState) -> Loadable<T>): StateFlow<Loadable<T>> =
        model.state.map(select).stateIn(scope, SharingStarted.Eagerly, select(model.state.value))

    private fun change(
        failureTitle: String,
        call: suspend () -> Result<Unit>,
    ) {
        scope.launch { call().onFailure { showError(project, failureTitle, it) } }
    }

    override fun dispose() {
        scope.cancel()
    }

    private companion object {
        val ENV_VAR_HELP =
            SectionHelp(
                "Environment variables are available to any job that uses this context.",
                "Using environment variables",
                "https://circleci.com/docs/guides/security/env-vars/",
            )
        const val ALL_MEMBERS = "All members"
        const val DELETE_CONTEXT_WARNING =
            "Its environment variables and restrictions are deleted with it, and jobs using it will fail."
        const val STATUS_GAP = 6

        /** What a restriction is to: its project's or group's name, the organization's members, or its expression. */
        fun label(
            restriction: ContextRestriction,
            orgId: String?,
        ): String =
            when {
                restriction.type == RestrictionType.GROUP && restriction.value == orgId -> ALL_MEMBERS
                restriction.type == RestrictionType.EXPRESSION -> restriction.value
                else -> restriction.name ?: restriction.value
            }

        /**
         * [components] one above the other, each between splitters, sharing
         * the height by their [weights]. Where the splitters are is kept.
         */
        fun stack(
            components: List<JComponent>,
            weights: List<Float>,
        ): JComponent {
            if (components.size == 1) return components.single()
            val proportion = weights.first() / weights.sum()
            return OnePixelSplitter(true, "CircleCI.ContextPage.Splitter.${components.size}", proportion).apply {
                firstComponent = components.first()
                secondComponent = stack(components.drop(1), weights.drop(1))
            }
        }
    }
}

private fun showError(
    project: Project,
    title: String,
    error: Throwable,
) {
    Messages.showErrorDialog(project, error.message ?: "Request failed", title)
}

private fun confirm(
    project: Project,
    title: String,
    message: String,
    yesText: String,
): Boolean =
    MessageDialogBuilder.yesNo(title, message)
        .yesText(yesText)
        .noText("Cancel")
        .asWarning()
        .ask(project)

private fun copy(text: String) {
    CopyPasteManager.getInstance().setContents(StringSelection(text))
}

/** An action on the whole page, with the shortcut of the IDE's own [shortcutFrom] action. */
private fun pageAction(
    text: String,
    icon: Icon,
    shortcutFrom: String?,
    perform: () -> Unit,
): DumbAwareAction =
    object : DumbAwareAction(text, null, icon) {
        init {
            shortcutFrom?.let { ActionManager.getInstance().getAction(it) }?.let(::copyShortcutFrom)
        }

        override fun actionPerformed(e: AnActionEvent) {
            perform()
        }
    }

/** A restriction, and what it's shown as. */
data class LabeledRestriction(
    val restriction: ContextRestriction,
    val label: String,
)

private fun <T, R> Loadable<T>.map(transform: (T) -> R): Loadable<R> =
    when (this) {
        is Loadable.Loaded -> Loadable.Loaded(transform(value))
        is Loadable.Failed -> this
        Loadable.Loading -> Loadable.Loading
        Loadable.NotLoaded -> Loadable.NotLoaded
    }

@Composable
private fun EnvVarHeader() {
    ContextRow {
        for (column in EnvVarColumn.entries) {
            Text(
                column.title,
                Modifier.weight(column.weight),
                color = JewelTheme.globalColors.text.info,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RowScope.EnvVarCells(
    envVar: EnvVar,
    onSelection: Boolean,
) {
    val detail = if (onSelection) Color.Unspecified else JewelTheme.globalColors.text.info
    Cell(envVar.name, EnvVarColumn.NAME.weight)
    Cell(envVar.maskedValue, EnvVarColumn.VALUE.weight, detail, JewelTheme.editorTextStyle)
    Cell(date(envVar.createdAt), EnvVarColumn.CREATED.weight, detail)
    Cell(date(envVar.updatedAt), EnvVarColumn.UPDATED.weight, detail)
}

@Composable
private fun RowScope.Cell(
    text: String,
    weight: Float,
    color: Color = Color.Unspecified,
    style: TextStyle = JewelTheme.defaultTextStyle,
) {
    Text(text, Modifier.weight(weight), color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * A restriction on one line: an expression's line breaks are only
 * whitespace, so they're spaces here, and whole in a tooltip.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip is built on TooltipArea
@Composable
private fun RowScope.RestrictionCell(
    row: LabeledRestriction,
    monospace: Boolean,
) {
    val oneLine = row.label.trim().replace(WHITESPACE, " ")
    val style = if (monospace) JewelTheme.editorTextStyle else JewelTheme.defaultTextStyle
    Tooltip(tooltip = { Text(row.label, style = style) }, modifier = Modifier.weight(1f)) {
        Text(oneLine, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (row.restriction.type == RestrictionType.PROJECT && row.restriction.name == null) {
        Text("(project ID)", color = JewelTheme.globalColors.text.info, style = JewelTheme.typography.small)
    }
}

private fun date(instant: Instant?): String =
    instant?.let {
        DateFormatUtil.formatPrettyDateTime(it.toEpochMilli())
    }.orEmpty()

private val WHITESPACE = Regex("\\s+")

private enum class EnvVarColumn(val title: String, val weight: Float) {
    NAME("Name", 0.34f),
    VALUE("Value", 0.2f),
    CREATED("Created", 0.23f),
    UPDATED("Last Updated", 0.23f),
}
