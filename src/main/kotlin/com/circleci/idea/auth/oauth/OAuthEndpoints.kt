package com.circleci.idea.auth.oauth

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

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
        private const val TIMEOUT_SECONDS = 30L

        /** The endpoints [hostUrl] (e.g. "https://circleci.com") advertises, else its /oauth ones. */
        fun discover(
            hostUrl: String,
            http: HttpClient,
        ): OAuthEndpoints {
            val host = hostUrl.trimEnd('/')
            val fallback = OAuthEndpoints("$host/oauth/authorize", "$host/oauth/token")
            return try {
                val request =
                    HttpRequest.newBuilder(URI("$host/.well-known/oauth-authorization-server"))
                        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                        .GET()
                        .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) return fallback
                val metadata = Gson().fromJson(response.body(), JsonObject::class.java) ?: return fallback
                OAuthEndpoints(
                    authorize = metadata.string("authorization_endpoint") ?: fallback.authorize,
                    token = metadata.string("token_endpoint") ?: fallback.token,
                )
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
