package com.circleci.idea.toolwindow.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/** Asks for the name of a context to create in [projectSlug]'s organization. */
class NewContextDialog(
    project: Project,
    private val projectSlug: String,
) : DialogWrapper(project) {
    private val nameField = JBTextField()

    val name: String get() = nameField.text.trim()

    init {
        title = "New Context"
        setOKButtonText("Create")
        init()
    }

    override fun createCenterPanel(): JComponent =
        panel {
            row("Name:") {
                cell(nameField).columns(COLUMNS_MEDIUM)
            }
            row {
                comment("Created in the organization of $projectSlug.")
            }
        }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? = if (name.isEmpty()) ValidationInfo("Enter a name", nameField) else null
}
