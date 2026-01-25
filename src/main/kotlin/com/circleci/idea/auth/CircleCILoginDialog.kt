package com.circleci.idea.auth

import com.circleci.idea.settings.CircleCISettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.event.ActionEvent
import javax.swing.*

/**
 * Login dialog for CircleCI authentication.
 * Allows users to enter their API token and optionally configure the host URL.
 */
class CircleCILoginDialog(
    private val project: Project
) : DialogWrapper(project) {

    private val tokenField = JBPasswordField()
    private val hostUrlField = JBTextField()
    private val authService = CircleCIAuthService.getInstance(project)

    init {
        title = "Login to CircleCI"
        hostUrlField.text = CircleCISettings.getInstance().hostUrl
        init()
    }

    override fun createCenterPanel(): JComponent {
        val instructionsLabel = JBLabel(
            "<html>Enter your CircleCI personal API token.<br>" +
                    "You can create one at: <a href='https://app.circleci.com/settings/user/tokens'>CircleCI Settings</a></html>"
        )
        instructionsLabel.setCopyable(true)

        val panel = FormBuilder.createFormBuilder()
            .addComponent(instructionsLabel, JBUI.scale(10))
            .addSeparator(JBUI.scale(10))
            .addLabeledComponent(JBLabel("API Token:"), tokenField, 1, false)
            .addLabeledComponent(JBLabel("Host URL:"), hostUrlField, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        panel.preferredSize = JBUI.size(450, 200)
        return panel
    }

    override fun doOKAction() {
        val token = String(tokenField.password)
        val hostUrl = hostUrlField.text.trim()

        if (token.isEmpty()) {
            Messages.showErrorDialog(
                project,
                "Please enter your CircleCI API token",
                "Token Required"
            )
            return
        }

        if (hostUrl.isEmpty() || !isValidUrl(hostUrl)) {
            Messages.showErrorDialog(
                project,
                "Please enter a valid CircleCI host URL",
                "Invalid URL"
            )
            return
        }

        // Attempt to login
        val result = authService.login(token, hostUrl)

        result.fold(
            onSuccess = { user ->
                Messages.showInfoMessage(
                    project,
                    "Successfully authenticated as ${user.name}",
                    "Login Successful"
                )
                super.doOKAction()
            },
            onFailure = { error ->
                Messages.showErrorDialog(
                    project,
                    "Authentication failed: ${error.message}",
                    "Login Failed"
                )
            }
        )
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction, cancelAction, createHelpAction())
    }

    private fun createHelpAction(): Action {
        return object : AbstractAction("How to get a token?") {
            override fun actionPerformed(e: ActionEvent?) {
                Messages.showInfoMessage(
                    project,
                    "To create a CircleCI personal API token:\n\n" +
                            "1. Go to https://app.circleci.com/settings/user/tokens\n" +
                            "2. Click 'Create New Token'\n" +
                            "3. Give it a name (e.g., 'IntelliJ Plugin')\n" +
                            "4. Copy the token and paste it here\n\n" +
                            "Note: The token will only be shown once, so make sure to copy it!",
                    "How to Get an API Token"
                )
            }
        }
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            val normalized = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else {
                url
            }
            java.net.URL(normalized)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun getPreferredFocusedComponent(): JComponent {
        return tokenField
    }
}
