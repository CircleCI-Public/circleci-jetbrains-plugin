package com.circleci.idea.toolwindow.tree

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.circleci.idea.job.JobDetailsService
import com.circleci.idea.job.JobRef
import com.circleci.idea.job.Placeholder
import com.circleci.idea.run.RunStatus
import com.circleci.idea.run.elapsedSince
import com.circleci.idea.toolwindow.actions.ApproveWorkflowAction
import com.circleci.idea.toolwindow.actions.CancelJobAction
import com.circleci.idea.toolwindow.actions.CancelWorkflowAction
import com.circleci.idea.toolwindow.actions.CopyJobNumberAction
import com.circleci.idea.toolwindow.actions.OpenJobDetailsAction
import com.circleci.idea.toolwindow.actions.OpenJobInBrowserAction
import com.circleci.idea.toolwindow.actions.OpenRunInBrowserAction
import com.circleci.idea.toolwindow.actions.OpenWorkflowInBrowserAction
import com.circleci.idea.toolwindow.actions.RerunJobWithSshAction
import com.circleci.idea.toolwindow.actions.RerunWorkflowAction
import com.circleci.idea.toolwindow.actions.RerunWorkflowFromFailedAction
import com.circleci.idea.toolwindow.actions.RerunWorkflowFromJobAction
import com.circleci.idea.toolwindow.actions.RerunWorkflowWithSshAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import org.jetbrains.jewel.bridge.retrieveColorOrUnspecified
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.IndeterminateHorizontalProgressBar
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.theme.linkStyle
import org.jetbrains.jewel.ui.typography
import java.time.Duration
import java.time.Instant

/**
 * The run tree, drawn with Jewel: runs take two lines, as the Pull Requests
 * list's rows do (the run's status and title, its details in grey
 * underneath, its branch and the avatar of whoever triggered it on the
 * right); workflows, jobs and messages take one. Statuses are small
 * coloured dots throughout.
 *
 * Double-clicking a job opens it; right-clicking offers the IDE's actions
 * for the row, which read it from the tool window's selection.
 */
class RunTreeView(
    project: Project,
    private val model: CircleCITreeModel,
) {
    private val jobDetailsService = project.getService(JobDetailsService::class.java)

    @OptIn(ExperimentalJewelApi::class)
    @Composable
    fun View() {
        val structure by model.structure.collectAsState()
        val loading by model.loading.collectAsState()
        Box(Modifier.fillMaxSize()) {
            // The run list empties while it first loads; a run or workflow shows its own loading row.
            if (model.root.childCount == 0) {
                Placeholder("Loading runs...")
            } else {
                val tree = remember(structure) { runTree(model.root) }
                val branchWidth = branchColumnWidth(structure)
                // Shared with the scrollbar, which Jewel's tree doesn't draw itself.
                VerticallyScrollableContainer(model.scroll, Modifier.fillMaxSize()) {
                    LazyTree(
                        tree = tree,
                        modifier = Modifier.fillMaxSize().focusable(),
                        treeState = model.treeState,
                        onElementClick = { element -> (element.data as? LoadMoreNode)?.let(model::loadMore) },
                        onElementDoubleClick = { element -> (element.data as? JobNode)?.let(::openJob) },
                    ) { element ->
                        val node = element.data
                        val onSelection = isSelected && isActive
                        Box(Modifier.fillMaxWidth().popupMenu(node)) {
                            if (node is RunNode) RunRow(node, branchWidth, onSelection) else LineRow(node, onSelection)
                        }
                    }
                }
            }
            // A thin progress bar along the top while runs load, as the Pull Requests list has.
            if (loading) IndeterminateHorizontalProgressBar(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    /**
     * One width for the branch column across the list, so it lines up: the
     * widest of the runs' refs. A run's row caps it at a share of its width,
     * past which a long branch name ends in "…".
     */
    @Composable
    private fun branchColumnWidth(structure: Int): Dp {
        val measurer = rememberTextMeasurer()
        val style = JewelTheme.typography.small
        val density = LocalDensity.current
        return remember(structure, style, density) {
            val refs = children(model.root).filterIsInstance<RunNode>().mapNotNull { it.getRefText() }
            val widest = refs.maxOfOrNull { measurer.measure(it, style).size.width } ?: return@remember 0.dp
            // A pixel's slack, so the widest doesn't round down into an ellipsis.
            with(density) { widest.toDp() } + 1.dp
        }
    }

    private fun openJob(node: JobNode) {
        jobDetailsService.openJob(JobRef.of(node.job, (node.parent as? WorkflowNode)?.workflow))
    }

    /** Right-clicking a row selects it and offers its actions. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun Modifier.popupMenu(node: CircleCITreeNode): Modifier =
        // Platforms differ on whether the press or the release is the popup trigger.
        onPointerEvent(PointerEventType.Press) { showPopupMenu(node, it) }
            .onPointerEvent(PointerEventType.Release) { showPopupMenu(node, it) }

    private fun showPopupMenu(
        node: CircleCITreeNode,
        event: PointerEvent,
    ) {
        val mouse = event.awtEventOrNull?.takeIf { it.isPopupTrigger } ?: return
        val actions = actionsFor(node) ?: return
        model.treeState.selectedKeys = setOf(keyOf(node))
        ActionManager.getInstance().createActionPopupMenu(ActionPlaces.TOOLWINDOW_POPUP, actions)
            .component.show(mouse.component, mouse.x, mouse.y)
    }

    private fun actionsFor(node: CircleCITreeNode): DefaultActionGroup? =
        when (node) {
            is WorkflowNode ->
                DefaultActionGroup().apply {
                    add(RerunWorkflowAction())
                    add(RerunWorkflowFromFailedAction())
                    add(RerunWorkflowWithSshAction())
                    add(CancelWorkflowAction())
                    addSeparator()
                    add(ApproveWorkflowAction())
                    addSeparator()
                    add(OpenWorkflowInBrowserAction())
                }
            is JobNode ->
                DefaultActionGroup().apply {
                    add(OpenJobDetailsAction())
                    addSeparator()
                    add(RerunWorkflowFromJobAction())
                    add(RerunJobWithSshAction())
                    add(CancelJobAction())
                    addSeparator()
                    add(CopyJobNumberAction())
                    add(OpenJobInBrowserAction())
                }
            is RunNode -> DefaultActionGroup(OpenRunInBrowserAction())
            else -> null
        }
}

/**
 * A run, in columns: its status and title, with its revision, age and
 * author underneath from the row's left edge; then its branch; then the
 * avatar of whoever triggered it, centred across both lines. The row spans
 * the tree's width, so the branch and avatar columns line up at its right
 * edge, and text that no longer fits ends in "…".
 */
@Composable
private fun RunRow(
    node: RunNode,
    branchWidth: Dp,
    onSelection: Boolean,
) {
    val run = node.run
    val detailColor = detailColor(onSelection)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val branchColumn = min(branchWidth, maxWidth * BRANCH_COLUMN_SHARE)
        Row(
            Modifier.fillMaxWidth().padding(vertical = VERTICAL_PADDING.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GAP.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(run.status)
                    Text(
                        node.getDisplayText(),
                        Modifier.padding(start = ICON_GAP.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    runDetails(node),
                    color = detailColor,
                    style = JewelTheme.typography.small,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (branchColumn > 0.dp) {
                Text(
                    node.getRefText().orEmpty(),
                    Modifier.width(branchColumn),
                    color = detailColor,
                    style = JewelTheme.typography.small,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RunAvatars.avatarUrl(run)?.let { Avatar(it, run.triggeredBy) }
        }
    }
}

/** The revision, age and who triggered the run, or failing that, whose commit it ran. */
private fun runDetails(node: RunNode): String =
    listOfNotNull(
        node.run.revision?.take(SHORT_REVISION_LENGTH),
        node.run.createdAt?.let(::formatTimeAgo),
        node.run.triggeredBy ?: node.run.commitAuthor,
    ).joinToString(" · ")

/** In a neutral ring: the status dot beside it already says how the run went. */
@OptIn(ExperimentalFoundationApi::class) // Jewel's tooltips are built on TooltipArea
@Composable
private fun Avatar(
    url: String,
    triggeredBy: String?,
) {
    val image by remember(url) { RunAvatars.getInstance().avatar(url) }.collectAsState()
    val ring =
        retrieveColorOrUnspecified(
            "Review.Avatar.Border.Status.Empty",
        ).takeOrElse { RING_COLOR.toComposeColor() }
    Tooltip(tooltip = { Text("Triggered by $triggeredBy") }) {
        Box(
            Modifier
                .padding(horizontal = AVATAR_PADDING.dp)
                .size(RunAvatars.SIZE.dp)
                .border(1.dp, ring, CircleShape)
                .padding(2.dp)
                .clip(CircleShape),
        ) {
            image?.let { Image(it, contentDescription = triggeredBy, modifier = Modifier.fillMaxSize()) }
        }
    }
}

/** Workflows, jobs and messages: a status dot, the text, and any detail in grey after it. */
@Composable
private fun LineRow(
    node: CircleCITreeNode,
    onSelection: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ICON_GAP.dp)) {
        when (node) {
            is ErrorNode -> StatusDot(RunStatus.FAILED)
            else -> node.getStatus()?.let { StatusDot(it) }
        }
        // Loading and empty rows are in italics, as the tree's other messages are.
        val muted = node is LoadingNode || node is EmptyNode
        val style = JewelTheme.defaultTextStyle.let { if (muted) it.copy(fontStyle = FontStyle.Italic) else it }
        Text(
            node.getDisplayText(),
            Modifier.weight(1f, fill = false),
            color = lineColor(node, onSelection),
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        lineDetail(node)?.let {
            Text(
                it,
                Modifier.padding(start = ICON_GAP.dp),
                color = detailColor(onSelection),
                style = JewelTheme.typography.small,
            )
        }
    }
}

@Composable
private fun lineColor(
    node: CircleCITreeNode,
    onSelection: Boolean,
): Color =
    when (node) {
        is ErrorNode -> JewelTheme.globalColors.text.error
        is LoadMoreNode -> JewelTheme.linkStyle.colors.content
        is LoadingNode, is EmptyNode -> detailColor(onSelection)
        else -> Color.Unspecified
    }

private fun lineDetail(node: CircleCITreeNode): String? =
    when (node) {
        is WorkflowNode -> node.workflow.createdAt?.let(::formatTimeAgo)
        // Queued jobs have no start time yet; running ones count up to now.
        is JobNode ->
            if (node.job.type == "approval") {
                "(approval)"
            } else {
                elapsedSince(
                    node.job.startedAt,
                    node.job.endedAt,
                )
            }
        else -> null
    }

/** Grey, unless on the focused selection's background, where it wouldn't read. */
@Composable
private fun detailColor(onSelection: Boolean): Color =
    if (onSelection) Color.Unspecified else JewelTheme.globalColors.text.info

/** A small dot in a status's colour, as the Pull Requests list marks its rows. */
@Composable
private fun StatusDot(status: RunStatus) {
    Box(Modifier.size(DOT.dp).background(statusColor(status).toComposeColor(), CircleShape))
}

/** Blue while running, green passed, red failed, purple on hold, grey otherwise. */
private fun statusColor(status: RunStatus): JBColor =
    when (status) {
        RunStatus.RUNNING -> BLUE
        RunStatus.SUCCESS -> GREEN
        RunStatus.FAILED, RunStatus.FAILING, RunStatus.ERROR -> RED
        RunStatus.ON_HOLD -> PURPLE
        else -> GREY
    }

private fun Color.takeOrElse(fallback: () -> Color): Color = if (this == Color.Unspecified) fallback() else this

/** A timestamp as "X ago" (e.g. "2h ago", "5m ago"). */
internal fun formatTimeAgo(time: Instant): String {
    val duration = Duration.between(time, Instant.now())
    return when {
        duration.toDays() > 0 -> "${duration.toDays()}d ago"
        duration.toHours() > 0 -> "${duration.toHours()}h ago"
        duration.toMinutes() > 0 -> "${duration.toMinutes()}m ago"
        else -> "just now"
    }
}

private const val SHORT_REVISION_LENGTH = 7
private const val GAP = 8
private const val ICON_GAP = 6
private const val VERTICAL_PADDING = 3
private const val AVATAR_PADDING = 6
private const val DOT = 8
private const val BRANCH_COLUMN_SHARE = 0.3f

private val BLUE = JBColor(0x3574F0, 0x548AF7)
private val GREEN = JBColor(0x369650, 0x5FAD65)
private val RED = JBColor(0xE55765, 0xDB5C5C)
private val PURPLE = JBColor(0x955AE0, 0xA571E6)
private val GREY = JBColor(0xA8ADBD, 0x6F737A)
private val RING_COLOR = JBColor(0xD3D5DB, 0x4E5157)
