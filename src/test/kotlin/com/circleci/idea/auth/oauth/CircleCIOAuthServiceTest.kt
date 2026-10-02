package com.circleci.idea.auth.oauth

import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class CircleCIOAuthServiceTest {
    private val circleci = FakeCircleCI()
    private val service = CircleCIOAuthService()
    private var opened: String? = null

    init {
        service.browse = { opened = it }
    }

    @After
    fun tearDown() {
        service.pendingRequest?.let {
            service.handleOAuthServerCallback(
                CALLBACK,
                mapOf("state" to listOf(it.state), "error" to listOf("test over")),
            )
        }
        circleci.close()
    }

    private fun authorize() = service.authorize(circleci.url, callbackPort = PORT, deviceId = DEVICE, os = "darwin")

    /** The authorize URL the browser was sent to, split into its address and parameters. */
    private fun openedPage(): Pair<String, Map<String, String>> {
        val uri = URI(opened!!)
        return "${uri.scheme}://${uri.authority}${uri.path}" to FakeCircleCI.form(uri.rawQuery)
    }

    @Test
    fun opensTheAdvertisedAuthorizePageWithPkce() {
        authorize()

        val (page, params) = openedPage()
        assertEquals("authorize page", circleci.advertisedAuthorize, page)
        assertEquals("response type", "code", params["response_type"])
        assertEquals("client", "circleci-jetbrains-plugin", params["client_id"])
        assertEquals("redirect", "http://127.0.0.1:$PORT/api/circleci/oauth/callback", params["redirect_uri"])
        assertEquals("challenge method", "S256", params["code_challenge_method"])
        assertEquals("device", DEVICE, params["device_id"])
        assertEquals("os", "darwin", params["os"])
        assertEquals("state", service.pendingRequest!!.state, params["state"])
    }

    @Test
    fun fallsBackToTheHostsOAuthPathsWithoutMetadata() {
        circleci.advertise = false

        authorize()

        assertEquals("authorize page", "${circleci.url}/oauth/authorize", openedPage().first)
    }

    @Test
    fun fallsBackWhenTheMetadataIsAWebPage() {
        circleci.metadataIsAWebPage = true

        authorize()

        assertEquals("authorize page", "${circleci.url}/oauth/authorize", openedPage().first)
    }

    @Test
    fun exchangesTheCodeForAToken() {
        val future = authorize()
        val params = openedPage().second

        val result =
            service.handleOAuthServerCallback(
                CALLBACK,
                mapOf("code" to listOf("the-code"), "state" to listOf(params["state"]!!)),
            )

        assertTrue("accepted", result!!.isAccepted)
        assertEquals("token", "oauth-token", future.get(5, TimeUnit.SECONDS).accessToken)
        val form = circleci.tokenRequests.single()
        assertEquals("grant", "authorization_code", form["grant_type"])
        assertEquals("client", "circleci-jetbrains-plugin", form["client_id"])
        assertEquals("code", "the-code", form["code"])
        assertEquals("redirect matches the authorize request's", params["redirect_uri"], form["redirect_uri"])
        assertTrue("verifier is 43+ characters", form["code_verifier"]!!.length >= 43)
        assertEquals("verifier matches the challenge", params["code_challenge"], s256(form["code_verifier"]!!))
        assertEquals("login done", null, service.pendingRequest)
    }

    @Test
    fun refusesACallbackWithAnotherState() {
        val future = authorize()

        val result =
            service.handleOAuthServerCallback(
                CALLBACK,
                mapOf("code" to listOf("the-code"), "state" to listOf("forged")),
            )

        assertFalse("refused", result!!.isAccepted)
        assertFalse("still waiting", future.isDone)
        assertTrue("no exchange", circleci.tokenRequests.isEmpty())
    }

    @Test
    fun failsWhenTheUserDenies() {
        val future = authorize()
        val state = service.pendingRequest!!.state

        service.handleOAuthServerCallback(
            CALLBACK,
            mapOf(
                "error" to listOf("access_denied"),
                "error_description" to listOf("The user denied access"),
                "state" to listOf(state),
            ),
        )

        val error = runCatching { future.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        assertEquals("why", "The user denied access", (error as ExecutionException).cause!!.message)
    }

    @Test
    fun failsWithTheTokenEndpointsError() {
        circleci.tokenStatus = 400
        circleci.tokenResponse = """{"error": "invalid_grant", "error_description": "The code has expired"}"""
        val future = authorize()

        service.handleOAuthServerCallback(
            CALLBACK,
            mapOf("code" to listOf("old"), "state" to listOf(service.pendingRequest!!.state)),
        )

        val error = runCatching { future.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        assertEquals("why", "The code has expired", (error as ExecutionException).cause!!.message)
    }

    @Test
    fun askingAgainWhileWaitingKeepsTheLogin() {
        val first = authorize()
        val page = opened

        val second = authorize()

        assertTrue("same login", first === second)
        assertEquals("browser opened once", page, opened)
    }

    @Test
    fun discoveryFallsBackWhenTheHostIsUnreachable() {
        val endpoints = OAuthEndpoints.discover("http://127.0.0.1:1", OkHttpClient())

        assertEquals(
            "fallback",
            OAuthEndpoints("http://127.0.0.1:1/oauth/authorize", "http://127.0.0.1:1/oauth/token"),
            endpoints,
        )
    }

    private companion object {
        const val PORT = 63342
        const val DEVICE = "8c4f3b4e-0d6a-4d55-9d5b-2f2f6f0d9a11"
        const val CALLBACK = "/api/circleci/oauth/callback"

        fun s256(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            )
    }
}
