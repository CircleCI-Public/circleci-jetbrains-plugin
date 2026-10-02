package com.circleci.idea.auth.oauth

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.collaboration.auth.credentials.Credentials
import com.intellij.collaboration.auth.credentials.SimpleCredentials
import com.intellij.collaboration.auth.services.OAuthCredentialsAcquirer
import com.intellij.collaboration.auth.services.OAuthCredentialsAcquirer.AcquireCredentialsResult
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Trades the authorization code for a token at CircleCI's token endpoint.
 * The token is a personal API token (good for 90 days, with no refresh),
 * used as any other.
 */
class CircleCITokenExchange(
    private val tokenUrl: String,
    private val redirectUri: String,
    private val codeVerifier: String,
    private val http: OkHttpClient,
) : OAuthCredentialsAcquirer<Credentials> {
    override fun acquireCredentials(code: String): AcquireCredentialsResult<Credentials> {
        val form =
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", CircleCIOAuthRequest.CLIENT_ID)
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("code_verifier", codeVerifier)
                .build()
        val request = Request.Builder().url(tokenUrl).post(form).header("Accept", "application/json").build()
        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = runCatching { Gson().fromJson(body, JsonObject::class.java) }.getOrNull()
            val token = json?.get("access_token")?.takeIf { it.isJsonPrimitive }?.asString
            when {
                response.isSuccessful && !token.isNullOrBlank() ->
                    AcquireCredentialsResult.Success(
                        SimpleCredentials(token),
                    )
                else -> AcquireCredentialsResult.Error(errorText(json, response.code))
            }
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
}
