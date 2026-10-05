package com.circleci.idea.context

import com.circleci.idea.api.clients.V3Page
import com.circleci.idea.api.models.NamedEntity
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.textCompletion.TextFieldWithCompletion
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

internal const val EXPRESSION_DOCS = "https://circleci.com/docs/guides/security/contexts/#expression-restrictions"

/**
 * A dialog that adds a restriction, staying open while it's added and
 * showing why if it couldn't be, so it can be put right.
 */
abstract class RestrictionDialog(
    project: Project,
    dialogTitle: String,
    private val submit: suspend (value: String) -> Result<Unit>,
) : DialogWrapper(project) {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT + ModalityState.any().asContextElement())

    init {
        title = dialogTitle
        setOKButtonText("Add")
        Disposer.register(disposable) { scope.cancel() }
    }

    /** What to restrict the context by, once one's chosen or typed. */
    protected abstract fun value(): String?

    override fun doOKAction() {
        val value = value() ?: return
        isOKActionEnabled = false
        setErrorText(null)
        scope.launch {
            submit(value).fold(
                onSuccess = { close(OK_EXIT_CODE) },
                onFailure = {
                    setErrorText(it.message ?: "Couldn't add the restriction")
                    isOKActionEnabled = true
                },
            )
        }
    }
}

/**
 * Picks a group to restrict a context to, from [groups], those it isn't
 * restricted to: the organization's groups, and all its members.
 */
class GroupRestrictionDialog(
    project: Project,
    groups: List<NamedEntity>,
    submit: suspend (groupId: String) -> Result<Unit>,
) : RestrictionDialog(project, "Add Group Restriction", submit) {
    private val list =
        JBList(groups).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = namedRenderer()
            if (groups.isNotEmpty()) selectedIndex = 0
        }

    init {
        ListSpeedSearch.installOn(list) { it.name }
        init()
    }

    override fun value(): String? = list.selectedValue?.id

    override fun createCenterPanel(): JComponent =
        panel {
            row {
                comment(
                    "Members of the group chosen can run jobs that use the context. Groups are created in your VCS.",
                )
            }
            row {
                cell(ScrollPaneFactory.createScrollPane(list)).align(Align.FILL).resizableColumn()
            }.resizableRow()
        }.apply { preferredSize = JBUI.size(PICKER_WIDTH, PICKER_HEIGHT) }

    override fun getPreferredFocusedComponent(): JComponent = list

    override fun doValidate(): ValidationInfo? =
        if (list.selectedValue == null) {
            ValidationInfo(
                "Choose a group",
                list,
            )
        } else {
            null
        }
}

/**
 * Picks a project to restrict a context to, searching the organization's
 * projects by name as it's typed, and leaving out those in [restricted].
 */
@OptIn(FlowPreview::class)
class ProjectRestrictionDialog(
    project: Project,
    private val restricted: Set<String>,
    private val search: suspend (name: String) -> Result<V3Page<NamedEntity>>,
    submit: suspend (projectId: String) -> Result<Unit>,
) : RestrictionDialog(project, "Add Project Restriction", submit) {
    private val searchField = SearchTextField(false)
    private val model = CollectionListModel<NamedEntity>()
    private val list =
        JBList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = namedRenderer()
        }
    private val status = JBLabel().apply { foreground = NamedColorUtil.getInactiveTextColor() }
    private val query = MutableStateFlow("")

    init {
        searchField.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    query.value = searchField.text
                }
            },
        )
        init()
        scope.launch { query.debounce(SEARCH_DELAY_MS).collectLatest(::show) }
    }

    private suspend fun show(name: String) {
        status.text = "Searching..."
        search(name).fold(
            onSuccess = { page ->
                val projects = page.items.filterNot { it.id in restricted }
                model.replaceAll(projects)
                if (projects.isNotEmpty()) list.selectedIndex = 0
                status.text =
                    when {
                        projects.isEmpty() -> "No projects found"
                        page.nextCursor != null -> "Showing the first ${page.items.size} matches. Type to narrow them."
                        else -> ""
                    }
            },
            onFailure = {
                model.removeAll()
                status.text = "Couldn't search the projects: ${it.message}"
            },
        )
    }

    override fun value(): String? = list.selectedValue?.id

    override fun createCenterPanel(): JComponent =
        panel {
            row {
                comment("Only the projects a context is restricted to can use it.")
            }
            row { cell(searchField).align(Align.FILL) }
            row {
                cell(ScrollPaneFactory.createScrollPane(list)).align(Align.FILL).resizableColumn()
            }.resizableRow()
            row { cell(status) }
        }.apply { preferredSize = JBUI.size(PICKER_WIDTH, PICKER_HEIGHT) }

    override fun getPreferredFocusedComponent(): JComponent = searchField.textEditor

    override fun doValidate(): ValidationInfo? =
        if (list.selectedValue == null) {
            ValidationInfo(
                "Choose a project",
                list,
            )
        } else {
            null
        }

    private companion object {
        const val SEARCH_DELAY_MS = 300L
    }
}

/**
 * Asks for an expression to restrict a context by, completing the values
 * it can use and checking it as it's typed, with what's wrong underlined.
 */
class ExpressionRestrictionDialog(
    project: Project,
    submit: suspend (expression: String) -> Result<Unit>,
) : RestrictionDialog(project, "Add Expression Restriction", submit) {
    private val field =
        TextFieldWithCompletion(project, ExpressionCompletion(), "", false, true, false, true).apply {
            setPreferredWidth(JBUI.scale(PICKER_WIDTH))
            addSettingsProvider { editor ->
                editor.settings.isUseSoftWraps = true
                editor.setVerticalScrollbarVisible(true)
            }
        }

    private var highlighters = emptyList<RangeHighlighter>()

    init {
        init()
        initValidation()
    }

    override fun value(): String? = field.text.trim().takeIf { it.isNotEmpty() }

    override fun createCenterPanel(): JComponent =
        panel {
            row {
                comment(
                    "Only pipelines whose values match the expression can use the context. " +
                        "See <a href=\"$EXPRESSION_DOCS\">Expression restrictions</a>.",
                )
            }
            row {
                cell(field).align(Align.FILL).resizableColumn()
                    .applyToComponent { preferredSize = JBUI.size(PICKER_WIDTH, EXPRESSION_HEIGHT) }
            }.resizableRow()
            row {
                comment("For example: <code>pipeline.git.branch == \"main\" and not job.ssh.enabled</code>")
            }
        }

    override fun getPreferredFocusedComponent(): JComponent = field

    override fun doValidate(): ValidationInfo? {
        val problems = ExpressionCheck.check(field.text)
        underline(problems)
        if (field.text.isBlank()) return ValidationInfo("Enter an expression", field).withOKEnabled()
        val problem = problems.firstOrNull { it.isError } ?: problems.firstOrNull() ?: return null
        val info = ValidationInfo(problem.message, field)
        return if (problem.isError) info else info.asWarning().withOKEnabled()
    }

    /** Underline each problem where it is in the field: in red for an error, as the editor does. */
    private fun underline(problems: List<ExpressionProblem>) {
        val editor = field.editor as? EditorEx ?: return
        val markup = editor.markupModel
        highlighters.forEach(markup::removeHighlighter)
        val length = editor.document.textLength
        highlighters =
            problems.map { problem ->
                // A problem at the end, such as a missing operand, underlines the last character.
                val start = problem.start.coerceAtMost(length - 1).coerceAtLeast(0)
                val end = (start + problem.length).coerceIn(minOf(start + 1, length), length)
                val key =
                    if (problem.isError) CodeInsightColors.ERRORS_ATTRIBUTES else CodeInsightColors.WARNINGS_ATTRIBUTES
                markup.addRangeHighlighter(key, start, end, HighlighterLayer.ERROR, HighlighterTargetArea.EXACT_RANGE)
            }
    }

    private companion object {
        const val EXPRESSION_HEIGHT = 90
    }
}

/** Completes the pipeline values an expression can use, and the language's words. */
private class ExpressionCompletion :
    TextFieldWithAutoCompletionListProvider<PipelineValue>(
        PipelineValues.all + ExpressionCheck.KEYWORDS.map { PipelineValue(it, "", null) },
    ) {
    override fun getLookupString(item: PipelineValue): String = item.name

    override fun getTypeText(item: PipelineValue): String? = item.type.takeIf { it.isNotEmpty() }

    override fun createLookupBuilder(item: PipelineValue): LookupElementBuilder =
        super.createLookupBuilder(item).withStrikeoutness(item.deprecated)

    override fun getPrefix(
        text: String,
        offset: Int,
    ): String = ExpressionCheck.prefixAt(text, offset)

    override fun createPrefixMatcher(prefix: String): PlainPrefixMatcher = PlainPrefixMatcher(prefix)

    // A value's dots, and a function's dashes, go on completing it.
    override fun acceptChar(c: Char): CharFilter.Result? = if (c in "._-") CharFilter.Result.ADD_TO_PREFIX else null
}

private fun namedRenderer() = textListCellRenderer<NamedEntity> { it.name }

private const val PICKER_WIDTH = 480
private const val PICKER_HEIGHT = 320
