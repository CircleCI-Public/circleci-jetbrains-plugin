package com.circleci.idea.toolwindow.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Asks for an environment variable's name and value: a new variable's, or a
 * new value for [existingName]. The value is typed into a password field, as
 * CircleCI never shows it again.
 */
class EnvVarDialog(
    project: Project,
    private val existingName: String?,
    private val ownerLabel: String,
) : DialogWrapper(project) {
    private val nameField = JBTextField(existingName.orEmpty())
    private val valueField = JBPasswordField()

    val name: String get() = nameField.text.trim()

    val value: String get() = String(valueField.password)

    init {
        title = if (existingName == null) "Add Environment Variable" else "Update $existingName"
        setOKButtonText(if (existingName == null) "Add" else "Update")
        init()
    }

    override fun createCenterPanel(): JComponent =
        panel {
            row("Name:") {
                cell(nameField).columns(COLUMNS_MEDIUM).enabled(existingName == null)
            }
            row("Value:") {
                cell(valueField).columns(COLUMNS_MEDIUM)
            }
            row {
                comment("Stored in $ownerLabel. CircleCI only shows a value's last few characters once it's saved.")
            }
        }

    override fun getPreferredFocusedComponent(): JComponent = if (existingName == null) nameField else valueField

    override fun doValidate(): ValidationInfo? =
        when {
            !NAME.matches(name) ->
                ValidationInfo("Use letters, digits and underscores, not starting with a digit", nameField)
            valueField.password.isEmpty() -> ValidationInfo("Enter a value", valueField)
            else -> null
        }

    private companion object {
        val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
