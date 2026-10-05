package com.circleci.idea.api

import com.circleci.idea.api.clients.ConfigApiClient
import com.circleci.idea.api.clients.ProjectApiClient
import com.circleci.idea.api.clients.SettingsApiClient
import com.circleci.idea.api.clients.WorkflowApiClient
import com.circleci.idea.api.models.ConfigCompileResponse
import com.circleci.idea.api.models.ConfigValidationResult
import com.circleci.idea.api.models.Context
import com.circleci.idea.api.models.EnvVar
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentLinkedQueue
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

    // Answered in turn, ahead of responseBody, for paged lists.
    private val responseQueue = ConcurrentLinkedQueue<String>()

    private lateinit var client: CircleCIApiClient

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val uri = URLDecoder.decode(exchange.requestURI.toString(), Charsets.UTF_8)
            requests += Recorded(exchange.requestMethod, uri, exchange.requestBody.readBytes().decodeToString())
            val bytes = (responseQueue.poll() ?: responseBody).toByteArray()
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

    private fun requestLines(): List<String> = requests.map { "${it.method} ${it.uri}" }

    fun testProjectEnvVarsFollowPageTokens() {
        responseQueue +=
            listOf(
                """{"items": [{"name": "A", "value": "xxxx1234"}], "next_page_token": "t2"}""",
                """{"items": [{"name": "B", "value": "xxxx5678"}], "next_page_token": null}""",
            )

        val vars = SettingsApiClient().listProjectEnvVars(client, "gh/org/repo").getOrThrow()

        assertEquals(
            "requests",
            listOf("GET /api/v2/project/gh/org/repo/envvar", "GET /api/v2/project/gh/org/repo/envvar?page-token=t2"),
            requestLines(),
        )
        assertEquals("variables", listOf(EnvVar("A", "xxxx1234"), EnvVar("B", "xxxx5678")), vars)
    }

    fun testSetProjectEnvVar() {
        responseBody = """{"name": "A", "value": "xxxx1234"}"""

        assertTrue("set succeeds", SettingsApiClient().setProjectEnvVar(client, "gh/org/repo", "A", "secret").isSuccess)

        val request = requests.single()
        assertEquals("request", "POST /api/v2/project/gh/org/repo/envvar", "${request.method} ${request.uri}")
        assertEquals("body", json("""{"name": "A", "value": "secret"}"""), json(request.body))
    }

    fun testDeleteProjectEnvVar() {
        responseBody = """{"message": "Environment variable deleted."}"""

        assertTrue("delete succeeds", SettingsApiClient().deleteProjectEnvVar(client, "gh/org/repo", "A").isSuccess)
        assertEquals("request", listOf("DELETE /api/v2/project/gh/org/repo/envvar/A"), requestLines())
    }

    fun testContextsAPageAtATime() {
        responseBody = """{"data": [{"id": "c-2", "attributes": {"name": "release"}}], "page": {"next": "n3"}}"""

        val page = SettingsApiClient().listContexts(client, "o-1", cursor = "n2").getOrThrow()

        assertEquals(
            "request",
            listOf("GET /api/v3/contexts?filter[org_id]=o-1&page[limit]=20&page[cursor]=n2"),
            requestLines(),
        )
        assertEquals("contexts", listOf(Context("c-2", "release")), page.items)
        assertEquals("next page", "n3", page.nextCursor)
    }

    fun testLastPageOfContextsHasNoCursor() {
        responseBody = """{"data": [{"id": "c-1", "attributes": {"name": "deploy"}}], "page": {"next": ""}}"""

        val page = SettingsApiClient().listContexts(client, "o-1", cursor = null).getOrThrow()

        assertEquals("request", listOf("GET /api/v3/contexts?filter[org_id]=o-1&page[limit]=20"), requestLines())
        assertNull("no next page", page.nextCursor)
    }

    fun testCreateContextInAnOrg() {
        responseBody = """{"data": {"id": "c-3", "attributes": {"name": "staging"}}}"""

        val context = SettingsApiClient().createContext(client, "o-1", "staging").getOrThrow()

        val request = requests.single()
        assertEquals("request", "POST /api/v3/contexts", "${request.method} ${request.uri}")
        assertEquals(
            "body",
            json("""{"data": {"attributes": {"name": "staging"}, "references": {"org": {"id": "o-1"}}}}"""),
            json(request.body),
        )
        assertEquals("created", Context("c-3", "staging"), context)
    }

    fun testContextEnvVarsShowTheirLastCharacters() {
        responseBody = """{"data": [{"attributes": {"name": "TOKEN", "truncated_value": "abcd"}}]}"""

        val vars = SettingsApiClient().listContextEnvVars(client, "c-1").getOrThrow()

        assertEquals("request", listOf("GET /api/v3/contexts/c-1/env-vars?page[limit]=100"), requestLines())
        assertEquals("variables", listOf(EnvVar("TOKEN", "****abcd")), vars)
    }

    fun testSetContextEnvVar() {
        responseBody = """{"data": {"attributes": {"name": "TOKEN"}}}"""

        assertTrue("set succeeds", SettingsApiClient().setContextEnvVar(client, "c-1", "TOKEN", "secret").isSuccess)

        val request = requests.single()
        assertEquals("request", "POST /api/v3/contexts/c-1/env-vars/set", "${request.method} ${request.uri}")
        assertEquals("body", json("""{"name": "TOKEN", "value": "secret"}"""), json(request.body))
    }

    fun testDeleteContextEnvVarNamesItInAFilter() {
        assertTrue("delete succeeds", SettingsApiClient().deleteContextEnvVar(client, "c-1", "TOKEN").isSuccess)
        assertEquals("request", listOf("DELETE /api/v3/contexts/c-1/env-vars?filter[name]=TOKEN"), requestLines())
    }
}
