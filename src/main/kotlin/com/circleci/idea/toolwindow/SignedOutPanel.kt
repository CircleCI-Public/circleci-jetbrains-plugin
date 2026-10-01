package com.circleci.idea.toolwindow

import com.circleci.idea.auth.CircleCILoginDialog
import com.circleci.idea.project.CircleCIProjectService
import com.circleci.idea.project.models.CircleCIProject
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ClientProperty
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JList

/**
 * What the CircleCI tool window shows until you log in, after the IDE's
 * GitHub Pull Requests view: the project the runs would be for, and ways to
 * log in.
 */
class SignedOutPanel(private val project: Project) : JBPanel<SignedOutPanel>(GridBagLayout()) {
    private val projectService = project.getService(CircleCIProjectService::class.java)

    private val projects = DefaultComboBoxModel<CircleCIProject>()

    // Set while refilling the projects, whose selection events aren't the user choosing one.
    private var refilling = false
    private val projectChoice =
        ComboBox(projects).apply {
            renderer = ProjectRenderer()
            addActionListener {
                if (refilling) return@addActionListener
                (selectedItem as? CircleCIProject)?.let { projectService.selectProjects(setOf(it.slug)) }
            }
        }
    private val projectRow =
        JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(GAP), 0)).apply {
            add(projectChoice, BorderLayout.CENTER)
            add(JBLabel(AllIcons.General.User), BorderLayout.EAST)
        }
    private val errorLabel = JBLabel().apply { foreground = UIUtil.getErrorForeground() }

    init {
        border = JBUI.Borders.empty(PADDING)

        // OAuth login is still to come; for now the button does nothing.
        val logInViaCircleCI =
            JButton("Log In via CircleCI...").apply {
                ClientProperty.put(this, DarculaButtonUI.DEFAULT_STYLE_KEY, true)
                toolTipText = "Coming soon: log in with your CircleCI account in the browser"
            }
        val logInWithToken = ActionLink("Log In with Token...") { CircleCILoginDialog(project).show() }

        val buttons =
            JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(logInViaCircleCI)
                add(JBLabel().apply { border = JBUI.Borders.emptyLeft(GAP * 2) })
                add(logInWithToken)
            }
        val hint =
            JBLabel("Change projects or account later in CircleCI Settings").apply {
                foreground = UIUtil.getContextHelpForeground()
            }

        val column =
            JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                listOf(projectRow, errorLabel, buttons, hint).forEach {
                    it.alignmentX = LEFT_ALIGNMENT
                    it.border = JBUI.Borders.emptyBottom(GAP * 2)
                    add(it)
                }
            }
        // Pinned to the top, filling the width, as the Pull Requests view sits.
        add(
            column,
            GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                weightx = 1.0
                weighty = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTH
            },
        )
        refresh(error = null)
    }

    /** Show the projects detected now, and why the last login failed, if it did. */
    fun refresh(error: String?) {
        val detected = projectService.projects.value
        val selected = projectService.getSelectedProjectObjects().firstOrNull()
        refilling = true
        try {
            projects.removeAllElements()
            detected.forEach { projects.addElement(it) }
            projects.selectedItem = selected ?: detected.firstOrNull()
        } finally {
            refilling = false
        }
        projectRow.isVisible = detected.isNotEmpty()

        errorLabel.text = error?.let { "Logged out: $it" }
        errorLabel.isVisible = error != null
    }

    /** "org/repo" with its VCS provider beside it, like the Pull Requests view's "org/repo origin". */
    private class ProjectRenderer : ColoredListCellRenderer<CircleCIProject>() {
        override fun customizeCellRenderer(
            list: JList<out CircleCIProject>,
            value: CircleCIProject?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            value ?: return
            append(value.getDisplayName())
            append(" ${value.vcsType.displayName}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private companion object {
        const val PADDING = 16
        const val GAP = 6
    }
}
