package com.circleci.idea.auth.oauth

import com.intellij.collaboration.auth.credentials.Credentials
import com.intellij.collaboration.auth.services.OAuthCredentialsAcquirer
import com.intellij.collaboration.auth.services.OAuthRequest
import com.intellij.collaboration.auth.services.PkceUtils
import com.intellij.util.Url
import com.intellij.util.Urls
import java.net.http.HttpClient
import java.security.SecureRandom
import java.util.Base64

/**
 * One browser login: the authorization code flow with PKCE (S256), as the
 * CircleCI CLI does it. The browser comes back to [authorizationCodeUrl] on
 * the IDE's built-in server, which CircleCI accepts as a loopback redirect.
 *
 * @param callbackPort The IDE's built-in server port
 * @param deviceId This install's ID, kept across logins: CircleCI replaces
 *   the token it last issued this device rather than adding another
 * @param os The OS CircleCI labels the token with, as the CLI names it
 */
class CircleCIOAuthRequest(
    val endpoints: OAuthEndpoints,
    callbackPort: Int,
    deviceId: String,
    os: String,
    http: HttpClient,
) : OAuthRequest<Credentials> {
    // RFC 7636 wants 43-128 characters; PkceUtils' own verifier is shorter.
    private val codeVerifier = randomToken()

    /** Sent to the browser and checked on the way back, so a callback this login didn't start is refused. */
    val state: String = randomToken()

    override val authorizationCodeUrl: Url = Urls.newFromEncoded("http://127.0.0.1:$callbackPort/$CALLBACK_PATH")

    override val credentialsAcquirer: OAuthCredentialsAcquirer<Credentials> =
        CircleCITokenExchange(endpoints.token, authorizationCodeUrl.toExternalForm(), codeVerifier, http)

    override val authUrlWithParameters: Url =
        Urls.newFromEncoded(endpoints.authorize).addParameters(
            mapOf(
                "response_type" to "code",
                "client_id" to CLIENT_ID,
                "redirect_uri" to authorizationCodeUrl.toExternalForm(),
                "code_challenge" to PkceUtils.generateShaCodeChallenge(codeVerifier, BASE64URL),
                "code_challenge_method" to "S256",
                "state" to state,
                "os" to os,
                "device_id" to deviceId,
            ),
        )

    companion object {
        /** The plugin's OAuth client, as registered with CircleCI's authentication service. */
        const val CLIENT_ID = "circleci-jetbrains-plugin"

        /** Where on the built-in server the browser returns: /api/<service name>/callback. */
        const val CALLBACK_PATH = "api/${CircleCIOAuthService.SERVICE_NAME}/callback"

        private val BASE64URL = Base64.getUrlEncoder().withoutPadding()
        private val random = SecureRandom()

        private fun randomToken(): String = BASE64URL.encodeToString(ByteArray(32).also(random::nextBytes))
    }
}
