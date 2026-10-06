package com.circleci.idea.auth

import com.circleci.idea.settings.CircleCISettings
import com.circleci.idea.state.User
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.Component
import javax.swing.JComponent

/**
 * "Log In to CircleCI" with a personal API token, laid out like the IDE's
 * own GitHub token login: the server, the token (with a button to create
 * one), and Log In, which checks the token before closing.
 *
 * @param parent The component to show over, when there's no project window (Settings, say)
 */
class CircleCILoginDialog(
    private val project: Project,
    parent: Component? = null,
) : DialogWrapper(project, parent, false, IdeModalityType.IDE) {
    private val authService = CircleCIAuthService.getInstance(project)
    private val serverField = JBTextField(serverName(CircleCISettings.getInstance().hostUrl))
    private val tokenField = JBPasswordField()

    init {
        title = "Log In to CircleCI"
        setOKButtonText("Log In")
        init()
    }

    override fun createCenterPanel(): JComponent =
        panel {
            row("Server:") {
                cell(serverField).align(AlignX.FILL)
            }
            row("Token:") {
                cell(tokenField).align(AlignX.FILL).resizableColumn()
                    .comment("Use a personal API token from your CircleCI user settings.")
                button("Generate...") { BrowserUtil.browse(tokensPage(hostUrl())) }
            }
        }.apply { preferredSize = preferredSize.apply { width = maxOf(width, PREFERRED_WIDTH) } }

    override fun getPreferredFocusedComponent(): JComponent = tokenField

    override fun doValidate(): ValidationInfo? {
        if (serverField.text.isBlank()) return ValidationInfo("Enter the CircleCI server", serverField)
        if (tokenField.password.isEmpty()) return ValidationInfo("Enter a token", tokenField)
        return null
    }

    // Called once doValidate passes.
    override fun doOKAction() {
        val token = String(tokenField.password)
        val hostUrl = hostUrl()
        // Checking the token is an API call: run it with a progress bar rather than on the EDT.
        val result =
            ProgressManager.getInstance().runProcessWithProgressSynchronously<Result<User>, Exception>(
                { runBlockingCancellable { authService.login(token, hostUrl) } },
                "Logging In to CircleCI",
                true,
                project,
            )
        result.fold(
            onSuccess = { super.doOKAction() },
            onFailure = { setErrorText("Couldn't log in: ${it.message}", tokenField) },
        )
    }

    /** The server as a URL: "circleci.com" becomes "https://circleci.com". */
    private fun hostUrl(): String {
        val server = serverField.text.trim().trimEnd('/')
        return if (server.startsWith("http://") || server.startsWith("https://")) server else "https://$server"
    }

    private companion object {
        const val PREFERRED_WIDTH = 520

        /** The server as typed: a URL without its scheme. */
        fun serverName(hostUrl: String): String = hostUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')

        /** Where to create a token: CircleCI cloud's web app lives on app.circleci.com. */
        fun tokensPage(hostUrl: String): String {
            val app = if (serverName(hostUrl) == "circleci.com") "https://app.circleci.com" else hostUrl
            return "$app/settings/user/tokens"
        }
    }
}
