package com.circleci.idea.auth

import com.circleci.idea.auth.oauth.CircleCIOAuthService
import com.circleci.idea.settings.CircleCISettings
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.observable.util.not
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.update.UiNotifyConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.Component
import javax.swing.Action
import javax.swing.JComponent
import kotlin.time.Duration.Companion.minutes

/**
 * "Log In to CircleCI" in the browser, as the IDE's GitHub plugin logs in:
 * the dialog opens the browser as it appears, waits for CircleCI to send it
 * back, and closes once the token it got works. Cancel abandons the login.
 *
 * @param parent The component to show over, when there's no project window (Settings, say)
 */
class CircleCIOAuthLoginDialog(
    private val project: Project,
    private val hostUrl: String = CircleCISettings.getInstance().hostUrl,
    parent: Component? = null,
) : DialogWrapper(project, parent, false, IdeModalityType.IDE) {
    private val authService = CircleCIAuthService.getInstance(project)
    private val oauthService = CircleCIOAuthService.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT + ModalityState.any().asContextElement())

    private val status = JBLabel()
    private val waiting = AtomicBooleanProperty(true)

    init {
        title = "Log In to CircleCI"
        Disposer.register(disposable) { scope.cancel() }
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun createCenterPanel(): JComponent =
        panel {
            row { cell(status) }
            row {
                comment("Finish logging in to ${serverName(hostUrl)} in your browser.")
            }.visibleIf(waiting)
            row {
                link("Open the login page again") {
                    oauthService.pendingRequest?.let { BrowserUtil.browse(it.authUrlWithParameters.toExternalForm()) }
                }
            }.visibleIf(waiting)
            row {
                link("Try again") { logIn() }
            }.visibleIf(waiting.not())
        }.also { UiNotifyConnector.doWhenFirstShown(it) { logIn() } }

    /** Open the browser and wait for it to come back with a token, then log in with it. */
    private fun logIn() {
        showWaiting()
        scope.launch {
            try {
                withTimeout(TIMEOUT) {
                    val future = withContext(Dispatchers.IO) { oauthService.authorize(hostUrl) }
                    val token =
                        try {
                            future.asDeferred().await().accessToken
                        } catch (e: CancellationException) {
                            // Cancel, closing the dialog, or the timeout: abandon the login, so the next starts afresh.
                            future.completeExceptionally(ProcessCanceledException(e))
                            throw e
                        }
                    withContext(Dispatchers.IO) { authService.login(token, hostUrl, AuthMethod.OAUTH) }.getOrThrow()
                }
                close(OK_EXIT_CODE)
            } catch (
                @Suppress("SwallowedException") e: TimeoutCancellationException,
            ) {
                showFailure("Your browser didn't come back from CircleCI within ${TIMEOUT.inWholeMinutes} minutes.")
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                showFailure("Couldn't log in: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun showWaiting() {
        status.text = "Logging in..."
        status.icon = AnimatedIcon.Default()
        status.foreground = NamedColorUtil.getInactiveTextColor()
        waiting.set(true)
    }

    private fun showFailure(message: String) {
        status.text = message
        status.icon = AllIcons.General.Error
        status.foreground = UIUtil.getLabelForeground()
        waiting.set(false)
    }

    private companion object {
        val TIMEOUT = 5.minutes

        fun serverName(hostUrl: String): String = hostUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
    }
}
