package com.circleci.idea.auth.oauth

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Where a CircleCI server authorizes and exchanges codes. The server
 * advertises these (on cloud they're app.circleci.com's); if it doesn't,
 * they're the host's /oauth/authorize and /oauth/token, which is all the
 * CLI and VS Code extension assume.
 */
data class OAuthEndpoints(
    val authorize: String,
    val token: String,
) {
    companion object {
        private val log = logger<OAuthEndpoints>()

        /** The endpoints [hostUrl] (e.g. "https://circleci.com") advertises, else its /oauth ones. */
        fun discover(
            hostUrl: String,
            http: OkHttpClient,
        ): OAuthEndpoints {
            val host = hostUrl.trimEnd('/')
            val fallback = OAuthEndpoints("$host/oauth/authorize", "$host/oauth/token")
            return try {
                val request = Request.Builder().url("$host/.well-known/oauth-authorization-server").get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return fallback
                    val metadata = Gson().fromJson(response.body?.string(), JsonObject::class.java) ?: return fallback
                    OAuthEndpoints(
                        authorize = metadata.string("authorization_endpoint") ?: fallback.authorize,
                        token = metadata.string("token_endpoint") ?: fallback.token,
                    )
                }
            } catch (e: IOException) {
                log.info("Couldn't read $host's OAuth metadata, using its /oauth endpoints: ${e.message}")
                fallback
            } catch (e: com.google.gson.JsonParseException) {
                log.info("$host's OAuth metadata isn't JSON, using its /oauth endpoints: ${e.message}")
                fallback
            }
        }

        private fun JsonObject.string(name: String): String? =
            get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    }
}
