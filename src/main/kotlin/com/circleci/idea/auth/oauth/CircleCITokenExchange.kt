package com.circleci.idea.auth.oauth

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.collaboration.auth.credentials.Credentials
import com.intellij.collaboration.auth.credentials.SimpleCredentials
import com.intellij.collaboration.auth.services.OAuthCredentialsAcquirer
import com.intellij.collaboration.auth.services.OAuthCredentialsAcquirer.AcquireCredentialsResult
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Trades the authorization code for a token at CircleCI's token endpoint.
 * The token is a personal API token (good for 90 days, with no refresh),
 * used as any other.
 */
class CircleCITokenExchange(
    private val tokenUrl: String,
    private val redirectUri: String,
    private val codeVerifier: String,
    private val http: HttpClient,
) : OAuthCredentialsAcquirer<Credentials> {
    override fun acquireCredentials(code: String): AcquireCredentialsResult<Credentials> {
        val form =
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to CircleCIOAuthRequest.CLIENT_ID,
                "code" to code,
                "redirect_uri" to redirectUri,
                "code_verifier" to codeVerifier,
            ).entries.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        val request =
            HttpRequest.newBuilder(URI(tokenUrl))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val json = runCatching { Gson().fromJson(response.body(), JsonObject::class.java) }.getOrNull()
        val token = json?.get("access_token")?.takeIf { it.isJsonPrimitive }?.asString
        return when {
            response.statusCode() in 200..299 && !token.isNullOrBlank() ->
                AcquireCredentialsResult.Success(
                    SimpleCredentials(token),
                )
            else -> AcquireCredentialsResult.Error(errorText(json, response.statusCode()))
        }
    }

    // An OAuth error response: {"error": "invalid_grant", "error_description": "..."}.
    private fun errorText(
        json: JsonObject?,
        status: Int,
    ): String {
        val description = json?.get("error_description")?.takeIf { it.isJsonPrimitive }?.asString
        val error = json?.get("error")?.takeIf { it.isJsonPrimitive }?.asString
        return description ?: error ?: "CircleCI didn't issue a token (HTTP $status)"
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
    }
}
