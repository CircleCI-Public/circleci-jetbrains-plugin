package com.circleci.idea.job

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circleci.idea.run.RunStatus
import com.circleci.idea.state.TestOutcome
import org.jetbrains.jewel.bridge.retrieveColorOrUnspecified
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import javax.swing.JComponent

/*
 * Pieces the job page's Compose views share.
 */

/** The status a test's outcome reads like, for its dot, or null for one with no dot. */
internal fun outcomeStatus(outcome: TestOutcome): RunStatus? =
    when (outcome) {
        TestOutcome.FAILURE -> RunStatus.FAILED
        TestOutcome.SKIPPED -> RunStatus.NOT_RUN
        TestOutcome.SUCCESS -> RunStatus.SUCCESS
        TestOutcome.OTHER -> null
    }

/** Text standing in for content that isn't there: loading, failed or empty. */
@Composable
internal fun Placeholder(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = JewelTheme.globalColors.text.info, textAlign = TextAlign.Center)
    }
}

/** A Swing component, such as a console, inside a Compose view. */
@Composable
internal fun SwingComponent(
    component: JComponent,
    modifier: Modifier = Modifier,
) {
    SwingPanel(background = JewelTheme.globalColors.panelBackground, factory = { component }, modifier = modifier)
}

/** A row's background in a list or table, as the IDE's tables draw selection. */
@Composable
internal fun Modifier.rowBackground(
    selected: Boolean,
    focused: Boolean,
): Modifier {
    if (!selected) return this
    val key = if (focused) "Table.selectionBackground" else "Table.selectionInactiveBackground"
    val color = retrieveColorOrUnspecified(key)
    return if (color == Color.Unspecified) this else background(color)
}
