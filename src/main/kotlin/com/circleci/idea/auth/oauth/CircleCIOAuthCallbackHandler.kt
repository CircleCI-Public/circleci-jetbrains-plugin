package com.circleci.idea.auth.oauth

import com.intellij.collaboration.auth.OAuthCallbackHandlerBase
import com.intellij.collaboration.auth.services.OAuthService
import com.intellij.util.io.isLocalOrigin
import io.netty.handler.codec.http.HttpRequest

/**
 * Where the browser comes back to after a CircleCI login, on the IDE's
 * built-in server: hands the code to [CircleCIOAuthService] and shows the
 * CircleCI CLI's page saying how it went ([CallbackPage]).
 */
class CircleCIOAuthCallbackHandler : OAuthCallbackHandlerBase() {
    override fun oauthService(): OAuthService<*> = CircleCIOAuthService.getInstance()

    /**
     * The browser arrives from CircleCI's pages, so its Origin or Referer is
     * CircleCI's (whichever page it last saw), which the built-in server
     * refuses by default. Allow any while a login is pending: the state check
     * refuses a callback the login didn't start, and nothing else is served.
     */
    override fun isOriginAllowed(request: HttpRequest): OriginCheckResult =
        if (request.isLocalOrigin() || CircleCIOAuthService.getInstance().pendingRequest != null) {
            OriginCheckResult.ALLOW
        } else {
            OriginCheckResult.FORBID
        }

    override fun handleOAuthResult(oAuthResult: OAuthService.OAuthResult<*>): AcceptCodeHandleResult {
        val failure = CircleCIOAuthService.getInstance().callbackFailure.get()
        return AcceptCodeHandleResult.Page(
            if (oAuthResult.isAccepted) {
                CallbackPage.success()
            } else {
                CallbackPage.failure(
                    failure ?: "Logging in didn't finish.",
                )
            },
        )
    }
}
