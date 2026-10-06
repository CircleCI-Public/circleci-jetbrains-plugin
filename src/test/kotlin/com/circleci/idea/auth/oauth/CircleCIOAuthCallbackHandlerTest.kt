package com.circleci.idea.auth.oauth

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.ide.BuiltInServerManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit

/**
 * The browser's return to the IDE's built-in server after logging in, as it
 * arrives from CircleCI's consent page: with CircleCI as its Referer.
 */
class CircleCIOAuthCallbackHandlerTest : BasePlatformTestCase() {
    private lateinit var circleci: FakeCircleCI
    private val service get() = CircleCIOAuthService.getInstance()
    private val port get() = BuiltInServerManager.getInstance().waitForStart().port
    private val http = HttpClient.newHttpClient()

    override fun setUp() {
        super.setUp()
        circleci = FakeCircleCI()
        service.browse = {}
    }

    override fun tearDown() {
        try {
            service.pendingRequest?.let {
                service.handleOAuthServerCallback(
                    CALLBACK,
                    mapOf("state" to listOf(it.state), "error" to listOf("test over")),
                )
            }
            circleci.close()
            http.shutdownNow()
        } finally {
            super.tearDown()
        }
    }

    private fun callback(query: String): Pair<Int, String> {
        val request =
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$CALLBACK?$query"))
                .header("Referer", "https://app.circleci.com/")
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    fun testLogsInFromCircleCIsConsentPage() {
        val future = service.authorize(circleci.url, callbackPort = port, deviceId = DEVICE, os = "linux")
        val state = service.pendingRequest!!.state

        val (status, page) = callback("code=the-code&state=$state")

        assertEquals("status", 200, status)
        assertTrue("says it worked: $page", page.contains("<h1>Authorization successful</h1>"))
        assertEquals("token", "oauth-token", future.get(5, TimeUnit.SECONDS).accessToken)
    }

    fun testRefusesAForgedState() {
        val future = service.authorize(circleci.url, callbackPort = port, deviceId = DEVICE, os = "linux")

        val (_, page) = callback("code=the-code&state=forged")

        assertTrue(
            "says why: $page",
            page.contains("<p>The state parameter did not match. This may indicate a CSRF attempt.</p>"),
        )
        assertFalse("still waiting", future.isDone)
    }

    fun testSaysWhyWhenTheUserDenies() {
        val future = service.authorize(circleci.url, callbackPort = port, deviceId = DEVICE, os = "linux")
        val state = service.pendingRequest!!.state

        val (_, page) = callback("error=access_denied&error_description=The+user+denied+access&state=$state")

        assertTrue("says why: $page", page.contains("<p>access_denied: The user denied access</p>"))
        assertTrue("login failed", future.isCompletedExceptionally)
    }

    fun testSaysWhyWhenTheTokenExchangeFails() {
        circleci.tokenStatus = 400
        circleci.tokenResponse = """{"error": "invalid_grant", "error_description": "The code has expired"}"""
        service.authorize(circleci.url, callbackPort = port, deviceId = DEVICE, os = "linux")
        val state = service.pendingRequest!!.state

        val (_, page) = callback("code=old&state=$state")

        assertTrue("says why: $page", page.contains("<p>The code has expired</p>"))
    }

    fun testRefusesOtherSitesWhenNoLoginIsPending() {
        val (status, _) = callback("code=the-code&state=anything")

        assertEquals("the built-in server doesn't serve it", 404, status)
    }

    private companion object {
        const val DEVICE = "8c4f3b4e-0d6a-4d55-9d5b-2f2f6f0d9a11"
        const val CALLBACK = "/api/circleci/oauth/callback"
    }
}
