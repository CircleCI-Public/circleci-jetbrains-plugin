package com.circleci.idea.toolwindow

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.circleci.idea.auth.CircleCILoginDialog
import com.circleci.idea.project.CircleCIProjectService
import com.circleci.idea.project.models.CircleCIProject
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.SimpleListItem
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import javax.swing.JComponent

/**
 * What the CircleCI tool window shows until you log in, after the IDE's
 * GitHub Pull Requests view: the project the runs would be for, and ways to
 * log in.
 */
class SignedOutView(private val project: Project) {
    private val projectService = project.getService(CircleCIProjectService::class.java)
    private val errorState = MutableStateFlow<String?>(null)

    val component: JComponent = JewelComposePanel { View() }

    /** Show why the last login failed, if it did. */
    fun showError(error: String?) {
        errorState.value = error
    }

    @Composable
    private fun View() {
        val error by errorState.collectAsState()
        // Pinned to the top, filling the width, as the Pull Requests view sits.
        Column(
            Modifier.fillMaxWidth().padding(PADDING.dp),
            verticalArrangement = Arrangement.spacedBy(GAP.dp * 2),
        ) {
            ProjectChoice()
            error?.let { Text("Logged out: $it", color = JewelTheme.globalColors.text.error) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GAP.dp * 2),
            ) {
                LogInViaCircleCI()
                Link("Log In with Token...", { CircleCILoginDialog(project).show() })
            }
            Text("Change projects or account later in CircleCI Settings", color = JewelTheme.globalColors.text.info)
        }
    }

    /**
     * The projects detected, "org/repo" with its VCS provider beside it,
     * like the Pull Requests view's "org/repo origin".
     */
    @OptIn(ExperimentalJewelApi::class) // ListComboBox over items other than strings
    @Composable
    private fun ProjectChoice() {
        val detected by projectService.projects.collectAsState()
        val selectedSlug by projectService.selectedProject.collectAsState()
        if (detected.isEmpty()) return
        val selected = detected.indexOfFirst { it.slug == selectedSlug }.coerceAtLeast(0)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GAP.dp)) {
            ListComboBox(
                items = detected,
                selectedIndex = selected,
                onSelectedItemChange = { projectService.selectProject(detected[it].slug) },
                itemKeys = { _, item -> item.slug },
                modifier = Modifier.weight(1f),
            ) { item, isSelected, isActive ->
                SimpleListItem(text = projectText(item), selected = isSelected, active = isActive)
            }
            Icon(AllIconsKeys.General.User, null)
        }
    }

    // OAuth login is still to come; for now the button does nothing.
    @OptIn(ExperimentalFoundationApi::class) // Jewel's tooltips are built on TooltipArea
    @Composable
    private fun LogInViaCircleCI() {
        Tooltip(tooltip = { Text("Coming soon: log in with your CircleCI account in the browser") }) {
            DefaultButton(onClick = {}) { Text("Log In via CircleCI...") }
        }
    }

    @Composable
    private fun projectText(item: CircleCIProject) =
        buildAnnotatedString {
            append(item.getDisplayName())
            withStyle(SpanStyle(color = JewelTheme.globalColors.text.info)) { append(" ${item.vcsType.displayName}") }
        }

    private companion object {
        const val PADDING = 16
        const val GAP = 6
    }
}
