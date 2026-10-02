package com.circleci.idea.auth.oauth

import com.circleci.idea.settings.CircleCISettings
import com.intellij.collaboration.auth.credentials.Credentials
import com.intellij.collaboration.auth.services.OAuthRequest
import com.intellij.collaboration.auth.services.OAuthService
import com.intellij.collaboration.auth.services.OAuthServiceBase
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import okhttp3.OkHttpClient
import org.jetbrains.ide.BuiltInServerManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit

/**
 * Logs in to CircleCI in the browser, as the IDE's GitHub plugin does for
 * GitHub: open the authorize page, take the code the browser brings back to
 * the IDE's built-in server ([CircleCIOAuthCallbackHandler]), and trade it
 * for a token. One login at a time; asking again while one waits gives the
 * same future.
 */
@Service(Service.Level.APP)
class CircleCIOAuthService : OAuthServiceBase<Credentials>() {
    private val log = logger<CircleCIOAuthService>()

    private val http =
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    /** Opens the authorize page; tests replace it. */
    internal var browse: (String) -> Unit = BrowserUtil::browse

    override val name: String = SERVICE_NAME

    /** The login waiting for the browser to come back, if there is one. */
    val pendingRequest: CircleCIOAuthRequest?
        get() = currentRequest.get()?.request as? CircleCIOAuthRequest

    /**
     * Start logging in to [hostUrl] (e.g. "https://circleci.com") in the
     * browser. The future completes with the token once the browser comes
     * back, or fails if CircleCI refuses; completing it exceptionally
     * abandons the login.
     */
    @RequiresBackgroundThread
    fun authorize(
        hostUrl: String,
        callbackPort: Int = BuiltInServerManager.getInstance().waitForStart().port,
        deviceId: String = CircleCISettings.getInstance().oauthDeviceId(),
        os: String = currentOs(),
    ): CompletableFuture<Credentials> {
        // A login already waiting keeps its request; OAuthServiceBase hands back its future.
        val request =
            pendingRequest ?: CircleCIOAuthRequest(
                OAuthEndpoints.discover(hostUrl, http),
                callbackPort,
                deviceId,
                os,
                http,
            )
        return authorize(request)
    }

    override fun startAuthorization(request: OAuthRequest<Credentials>) {
        browse(request.authUrlWithParameters.toExternalForm())
    }

    /**
     * Why the callback this thread last handled was refused, for the page
     * the browser is shown; [CircleCIOAuthCallbackHandler] reads it on the
     * same thread straight after.
     */
    internal val callbackFailure = ThreadLocal<String?>()

    /**
     * Take the code the browser brought back. A callback whose state isn't
     * the pending login's is refused, leaving that login waiting.
     */
    override fun handleOAuthServerCallback(
        path: String,
        parameters: Map<String, List<String>>,
    ): OAuthService.OAuthResult<Credentials>? {
        callbackFailure.remove()
        val pending = currentRequest.get()?.takeIf { it.request is CircleCIOAuthRequest } ?: return null
        val request = pending.request as CircleCIOAuthRequest

        fun param(name: String) = parameters[name]?.firstOrNull()?.takeIf { it.isNotEmpty() }

        // The reasons are the CircleCI CLI's, in the order it checks them.
        val error = param("error")
        val refusal =
            when {
                error != null -> param("error_description")?.let { "$error: $it" } ?: error
                param(
                    "state",
                ) != request.state -> "The state parameter did not match. This may indicate a CSRF attempt."
                param("code") == null -> "The authorization response did not include a code."
                else -> null
            }
        if (param("state") != request.state) {
            log.warn("Ignoring an OAuth callback that doesn't match the pending login's state")
            callbackFailure.set(refusal)
            return OAuthService.OAuthResult(request, false)
        }

        val result = super.handleOAuthServerCallback(path, parameters)
        if (result?.isAccepted == false) {
            // Refused above, or the token exchange failed: say why it failed.
            callbackFailure.set(refusal ?: failureOf(pending.result) ?: "CircleCI didn't issue a token.")
        }
        return result
    }

    /** Why [future] failed, if it has. */
    private fun failureOf(future: CompletableFuture<*>): String? {
        if (!future.isCompletedExceptionally) return null
        val error = runCatching { future.getNow(null) }.exceptionOrNull()
        return ((error as? CompletionException)?.cause ?: error)?.message
    }

    // CircleCI has no revoke endpoint; tokens are revoked from the user's OAuth clients settings page.
    override fun revokeToken(token: String) = Unit

    companion object {
        /** The REST service name: the callback is served under /api/circleci/oauth. */
        const val SERVICE_NAME = "circleci/oauth"

        private const val TIMEOUT_SECONDS = 30L

        fun getInstance(): CircleCIOAuthService = service()

        /** The OS as the CLI names it (Go's GOOS), which CircleCI labels the token with. */
        fun currentOs(): String =
            when {
                SystemInfo.isMac -> "darwin"
                SystemInfo.isWindows -> "windows"
                else -> "linux"
            }
    }
}
