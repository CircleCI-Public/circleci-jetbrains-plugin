package com.circleci.idea.api

import com.circleci.idea.api.clients.ConfigApiClient
import com.circleci.idea.api.clients.ProjectApiClient
import com.circleci.idea.api.clients.WorkflowApiClient
import com.circleci.idea.api.models.ConfigCompileResponse
import com.circleci.idea.api.models.ConfigValidationResult
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The API clients against a local HTTP server, checking what they send and
 * how they read V3 responses. A platform test, because the client logs
 * through the IDE.
 */
class CircleCIApiServiceTest : BasePlatformTestCase() {
    private val gson = Gson()

    /** A request the fake API got: method, path and query, and body. */
    private data class Recorded(val method: String, val uri: String, val body: String)

    private lateinit var server: HttpServer

    // Written on the server's thread, read on the test's.
    private val requests = CopyOnWriteArrayList<Recorded>()
    private var responseBody = "{}"

    private lateinit var client: CircleCIApiClient

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val uri = URLDecoder.decode(exchange.requestURI.toString(), Charsets.UTF_8)
            requests += Recorded(exchange.requestMethod, uri, exchange.requestBody.readBytes().decodeToString())
            val bytes = responseBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        client = CircleCIApiClient(baseUrl = "http://127.0.0.1:${server.address.port}", token = "token")
    }

    override fun tearDown() {
        try {
            server.stop(0)
        } finally {
            super.tearDown()
        }
    }

    private fun json(text: String): JsonObject = gson.fromJson(text, JsonObject::class.java)

    fun testCurrentUserFromV3Users() {
        responseBody =
            """
            {"data": [{"id": "u-1", "attributes": {"name": "Ada", "login": "ada", "avatar_url": "https://a/b.png"}}]}
            """

        val user = ProjectApiClient().getCurrentUser(client).getOrThrow()

        assertEquals(
            "request",
            "GET /api/v3/users?filter[user_id]=me",
            requests.single().let { "${it.method} ${it.uri}" },
        )
        assertEquals("id", "u-1", user.id)
        assertEquals("login", "ada", user.login)
        assertEquals("name", "Ada", user.name)
        assertEquals("avatar", "https://a/b.png", user.avatarUrl)
    }

    fun testCurrentUserFailsWhenNoneReturned() {
        responseBody = """{"data": []}"""

        // The client logs the failure as an error, which fails a platform test unless expected.
        var result: Result<*>? = null
        LoggedErrorProcessor.executeAndReturnLoggedError { result = ProjectApiClient().getCurrentUser(client) }

        assertTrue("an empty list is a failure", result!!.isFailure)
    }

    fun testRerunSendsV3FieldNames() {
        responseBody = """{"data": {"id": "w-2"}}"""

        WorkflowApiClient().rerunWorkflow(client, "w-1", fromFailed = true, enableSsh = true, jobs = listOf("j-1"))

        val request = requests.single()
        assertEquals("request", "POST /api/v3/workflows/w-1/rerun", "${request.method} ${request.uri}")
        assertEquals(
            "body",
            json("""{"is_from_failed": true, "is_ssh_enabled": true, "jobs": ["j-1"]}"""),
            json(request.body),
        )
    }

    fun testCancelUsesV3() {
        responseBody = """{"data": {"id": "w-1"}}"""

        assertTrue("cancel succeeds", WorkflowApiClient().cancelWorkflow(client, "w-1").isSuccess)
        assertEquals("request", "POST /api/v3/workflows/w-1/cancel", requests.single().let { "${it.method} ${it.uri}" })
    }

    fun testCompileRequestShape() {
        responseBody = """{"data": {"attributes": {"outcome": "succeeded"}}}"""

        ConfigApiClient().validateConfig(client, "version: 2.1", "main", "org-1")

        val request = requests.single()
        assertEquals("request", "POST /api/v3/configs/compile", "${request.method} ${request.uri}")
        assertEquals(
            "body",
            json(
                """
                {"data": {
                  "attributes": {"config": "version: 2.1", "pipeline_values": {"pipeline.git.branch": "main"}},
                  "references": {"org": {"id": "org-1"}}
                }}
                """,
            ),
            json(request.body),
        )
    }

    fun testCompileRequestWithoutOrg() {
        responseBody = """{"data": {"attributes": {"outcome": "succeeded"}}}"""

        ConfigApiClient().validateConfig(client, "version: 2.1", "main", orgId = null)

        assertFalse("no references", json(requests.single().body)["data"].asJsonObject.has("references"))
    }

    fun testCompileSucceeded() {
        val response =
            gson.fromJson(
                """
                {"data": {"attributes": {"phase": "ended", "outcome": "succeeded", "compiled_config": "jobs: {}"}}}
                """,
                ConfigCompileResponse::class.java,
            )

        val result = ConfigValidationResult.from(response)

        assertTrue("valid", result.valid)
        assertTrue("no errors", result.errors.isEmpty())
        assertEquals("compiled config", "jobs: {}", result.compiledConfig)
    }

    fun testCompileFailed() {
        val response =
            gson.fromJson(
                """
                {
                  "data": {"attributes": {"phase": "ended", "outcome": "failed"}},
                  "meta": {"messages": [{"title": "Invalid workflow name"}, {"title": "Unknown job reference"}]}
                }
                """,
                ConfigCompileResponse::class.java,
            )

        val result = ConfigValidationResult.from(response)

        assertFalse("invalid", result.valid)
        assertEquals("errors", listOf("Invalid workflow name", "Unknown job reference"), result.errors)
        assertNull("no compiled config", result.compiledConfig)
    }
}
