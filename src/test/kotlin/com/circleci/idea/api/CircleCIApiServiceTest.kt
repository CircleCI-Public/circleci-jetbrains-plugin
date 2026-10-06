package com.circleci.idea.api

import com.circleci.idea.api.clients.ConfigApiClient
import com.circleci.idea.api.clients.ContextApiClient
import com.circleci.idea.api.clients.ProjectApiClient
import com.circleci.idea.api.clients.RunApiClient
import com.circleci.idea.api.clients.SettingsApiClient
import com.circleci.idea.api.clients.WorkflowApiClient
import com.circleci.idea.api.models.ConfigCompileResponse
import com.circleci.idea.api.models.ConfigValidationResult
import com.circleci.idea.api.models.Context
import com.circleci.idea.api.models.ContextDetail
import com.circleci.idea.api.models.ContextRestriction
import com.circleci.idea.api.models.EnvVar
import com.circleci.idea.api.models.NamedEntity
import com.circleci.idea.api.models.RestrictionType
import com.circleci.idea.project.models.Organization
import com.circleci.idea.project.models.ProjectInfo
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

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
    private val tokens = CopyOnWriteArrayList<String>()
    private var responseBody = "{}"
    private var responseStatus = 200

    // Answered in turn, ahead of responseBody, for paged lists.
    private val responseQueue = ConcurrentLinkedQueue<String>()

    private lateinit var client: CircleCIApiClient

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val uri = URLDecoder.decode(exchange.requestURI.toString(), Charsets.UTF_8)
            requests += Recorded(exchange.requestMethod, uri, exchange.requestBody.readBytes().decodeToString())
            tokens += exchange.requestHeaders.getFirst("Circle-Token").orEmpty()
            val bytes = (responseQueue.poll() ?: responseBody).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
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

    fun testCurrentUserFromV3Users() =
        runBlocking<Unit> {
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

    fun testInitializeWithANewTokenSendsIt() =
        runBlocking<Unit> {
            responseBody = """{"data": [{"id": "u-1", "attributes": {"name": "Ada", "login": "ada"}}]}"""
            val url = "http://127.0.0.1:${server.address.port}"
            val service = CircleCIApiService()

            service.initialize("one", url)
            service.getCurrentUser().getOrThrow()
            service.initialize("one", url)
            service.getCurrentUser().getOrThrow()
            service.initialize("two", url)
            service.getCurrentUser().getOrThrow()

            assertEquals("tokens sent", listOf("one", "one", "two"), tokens.toList())
        }

    fun testCancellingARequestCancelsItsCall() =
        runBlocking<Unit> {
            val release = CountDownLatch(1)
            server.createContext("/slow") { exchange ->
                release.await(10, TimeUnit.SECONDS)
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            val request = launch(Dispatchers.IO) { client.get("/slow") }
            delay(200)

            val cancelled = measureTimeMillis { request.cancelAndJoin() }
            release.countDown()

            assertTrue("cancelled without waiting for the response, in ${cancelled}ms", cancelled < 2_000)
        }

    fun testCurrentUserFailsWhenNoneReturned() =
        runBlocking<Unit> {
            responseBody = """{"data": []}"""

            // The client logs the failure as an error, which fails a platform test unless expected.
            var result: Result<*>? = null
            LoggedErrorProcessor.executeAndReturnLoggedError {
                result =
                    runBlocking {
                        ProjectApiClient().getCurrentUser(
                            client,
                        )
                    }
            }

            assertTrue("an empty list is a failure", result!!.isFailure)
        }

    fun testRerunSendsV3FieldNames() =
        runBlocking<Unit> {
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

    fun testCancelUsesV3() =
        runBlocking<Unit> {
            responseBody = """{"data": {"id": "w-1"}}"""

            assertTrue("cancel succeeds", WorkflowApiClient().cancelWorkflow(client, "w-1").isSuccess)
            assertEquals(
                "request",
                "POST /api/v3/workflows/w-1/cancel",
                requests.single().let { "${it.method} ${it.uri}" },
            )
        }

    fun testCompileRequestShape() =
        runBlocking<Unit> {
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

    fun testCompileRequestWithoutOrg() =
        runBlocking<Unit> {
            responseBody = """{"data": {"attributes": {"outcome": "succeeded"}}}"""

            ConfigApiClient().validateConfig(client, "version: 2.1", "main", orgId = null)

            assertFalse("no references", json(requests.single().body)["data"].asJsonObject.has("references"))
        }

    fun testCompileSucceeded() =
        runBlocking<Unit> {
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

    fun testCompileFailed() =
        runBlocking<Unit> {
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

    fun testProjectEnvVarsFollowPageTokens() =
        runBlocking<Unit> {
            responseQueue +=
                listOf(
                    """{"items": [{"name": "A", "value": "xxxx1234"}], "next_page_token": "t2"}""",
                    """{"items": [{"name": "B", "value": "xxxx5678"}], "next_page_token": null}""",
                )

            val vars = SettingsApiClient().listProjectEnvVars(client, "gh/org/repo").getOrThrow()

            assertEquals(
                "requests",
                listOf(
                    "GET /api/v2/project/gh/org/repo/envvar",
                    "GET /api/v2/project/gh/org/repo/envvar?page-token=t2",
                ),
                requestLines(),
            )
            assertEquals("variables", listOf(EnvVar("A", "xxxx1234"), EnvVar("B", "xxxx5678")), vars)
        }

    fun testSetProjectEnvVar() =
        runBlocking<Unit> {
            responseBody = """{"name": "A", "value": "xxxx1234"}"""

            assertTrue(
                "set succeeds",
                SettingsApiClient().setProjectEnvVar(client, "gh/org/repo", "A", "secret").isSuccess,
            )

            val request = requests.single()
            assertEquals("request", "POST /api/v2/project/gh/org/repo/envvar", "${request.method} ${request.uri}")
            assertEquals("body", json("""{"name": "A", "value": "secret"}"""), json(request.body))
        }

    fun testDeleteProjectEnvVar() =
        runBlocking<Unit> {
            responseBody = """{"message": "Environment variable deleted."}"""

            assertTrue("delete succeeds", SettingsApiClient().deleteProjectEnvVar(client, "gh/org/repo", "A").isSuccess)
            assertEquals("request", listOf("DELETE /api/v2/project/gh/org/repo/envvar/A"), requestLines())
        }

    fun testContextsAPageAtATime() =
        runBlocking<Unit> {
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

    fun testLastPageOfContextsHasNoCursor() =
        runBlocking<Unit> {
            responseBody = """{"data": [{"id": "c-1", "attributes": {"name": "deploy"}}], "page": {"next": ""}}"""

            val page = SettingsApiClient().listContexts(client, "o-1", cursor = null).getOrThrow()

            assertEquals("request", listOf("GET /api/v3/contexts?filter[org_id]=o-1&page[limit]=20"), requestLines())
            assertNull("no next page", page.nextCursor)
        }

    fun testCreateContextInAnOrg() =
        runBlocking<Unit> {
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

    fun testDeleteContext() =
        runBlocking<Unit> {
            assertTrue("delete succeeds", SettingsApiClient().deleteContext(client, "c-1").isSuccess)
            assertEquals("request", listOf("DELETE /api/v3/contexts/c-1"), requestLines())
        }

    fun testContextEnvVarsShowTheirLastCharacters() =
        runBlocking<Unit> {
            responseBody = """{"data": [{"attributes": {"name": "TOKEN", "truncated_value": "abcd"}}]}"""

            val vars = SettingsApiClient().listContextEnvVars(client, "c-1").getOrThrow()

            assertEquals("request", listOf("GET /api/v3/contexts/c-1/env-vars?page[limit]=100"), requestLines())
            assertEquals("variables", listOf(EnvVar("TOKEN", "****abcd")), vars)
        }

    fun testSetContextEnvVar() =
        runBlocking<Unit> {
            responseBody = """{"data": {"attributes": {"name": "TOKEN"}}}"""

            assertTrue("set succeeds", SettingsApiClient().setContextEnvVar(client, "c-1", "TOKEN", "secret").isSuccess)

            val request = requests.single()
            assertEquals("request", "POST /api/v3/contexts/c-1/env-vars/set", "${request.method} ${request.uri}")
            assertEquals("body", json("""{"name": "TOKEN", "value": "secret"}"""), json(request.body))
        }

    fun testDeleteContextEnvVarNamesItInAFilter() =
        runBlocking<Unit> {
            assertTrue("delete succeeds", SettingsApiClient().deleteContextEnvVar(client, "c-1", "TOKEN").isSuccess)
            assertEquals("request", listOf("DELETE /api/v3/contexts/c-1/env-vars?filter[name]=TOKEN"), requestLines())
        }

    fun testContextWithItsOrganization() =
        runBlocking<Unit> {
            responseBody =
                """
            {"data": {"id": "c-1", "attributes": {"name": "deploy", "created_at": "2026-01-02T03:04:05Z"},
                      "references": {"org": {"id": "o-1"}}}}
            """

            val context = ContextApiClient().getContext(client, "c-1").getOrThrow()

            assertEquals("request", listOf("GET /api/v3/contexts/c-1"), requestLines())
            assertEquals(
                "context",
                ContextDetail("c-1", "deploy", "o-1", Instant.parse("2026-01-02T03:04:05Z")),
                context,
            )
        }

    fun testContextEnvVarsWithTheirDates() =
        runBlocking<Unit> {
            responseBody =
                """
            {"data": [{"attributes": {"name": "TOKEN", "truncated_value": "abcd",
                                      "created_at": "2026-01-02T03:04:05Z", "updated_at": "2026-02-03T04:05:06Z"}}]}
            """

            val envVar = SettingsApiClient().listContextEnvVars(client, "c-1").getOrThrow().single()

            assertEquals("created", Instant.parse("2026-01-02T03:04:05Z"), envVar.createdAt)
            assertEquals("updated", Instant.parse("2026-02-03T04:05:06Z"), envVar.updatedAt)
        }

    fun testRestrictionsOfTheTypesKnown() =
        runBlocking<Unit> {
            responseBody =
                """
            {"data": [
              {"id": "r-1", "attributes": {"restriction_type": "project", "match_pattern": "p-1", "name": "app"}},
              {"id": "r-2", "attributes": {"restriction_type": "expression", "match_pattern": "pipeline.git.branch == \"main\""}},
              {"id": "r-3", "attributes": {"restriction_type": "group", "match_pattern": "g-1", "name": ""}},
              {"id": "r-4", "attributes": {"restriction_type": "someday", "match_pattern": "x"}}
            ]}
            """

            val restrictions = ContextApiClient().listContextRestrictions(client, "c-1").getOrThrow()

            assertEquals("request", listOf("GET /api/v3/context-restrictions?filter[context_id]=c-1"), requestLines())
            assertEquals(
                "restrictions, without the unknown type, and with no name where it's empty",
                listOf(
                    ContextRestriction("r-1", RestrictionType.PROJECT, "p-1", "app"),
                    ContextRestriction("r-2", RestrictionType.EXPRESSION, "pipeline.git.branch == \"main\"", null),
                    ContextRestriction("r-3", RestrictionType.GROUP, "g-1", null),
                ),
                restrictions,
            )
        }

    fun testCreateRestrictionOnTheContext() =
        runBlocking<Unit> {
            responseBody = """{"data": {"id": "r-1"}}"""

            val result = ContextApiClient().createContextRestriction(client, "c-1", RestrictionType.EXPRESSION, "true")

            assertTrue("create succeeds", result.isSuccess)
            val request = requests.single()
            assertEquals("request", "POST /api/v3/context-restrictions", "${request.method} ${request.uri}")
            assertEquals(
                "body",
                json(
                    """
                {"data": {"attributes": {"restriction_type": "expression", "match_pattern": "true"},
                          "references": {"context": {"id": "c-1"}}}}
                """,
                ),
                json(request.body),
            )
        }

    fun testRejectedRestrictionSaysWhy() =
        runBlocking<Unit> {
            responseStatus = 400
            responseBody = """{"error": {"title": "Invalid restriction.", "detail": "Unexpected character '&'"}}"""

            // The client logs the failure as errors, which fail a platform test unless they're let through.
            var result: Result<*>? = null
            val ignoreErrors =
                object : LoggedErrorProcessor() {
                    override fun processError(
                        category: String,
                        message: String,
                        details: Array<String>,
                        t: Throwable?,
                    ): Set<Action> = Action.NONE
                }
            LoggedErrorProcessor.executeWith<RuntimeException>(ignoreErrors) {
                result =
                    runBlocking {
                        ContextApiClient().createContextRestriction(
                            client,
                            "c-1",
                            RestrictionType.EXPRESSION,
                            "a && b",
                        )
                    }
            }

            assertEquals(
                "message",
                "Invalid restriction: Unexpected character '&'",
                result!!.exceptionOrNull()?.message,
            )
        }

    fun testDeleteRestrictionNamesItsContext() =
        runBlocking<Unit> {
            assertTrue("delete succeeds", ContextApiClient().deleteContextRestriction(client, "c-1", "r-1").isSuccess)
            assertEquals(
                "request",
                listOf("DELETE /api/v3/context-restrictions/r-1?filter[context_id]=c-1"),
                requestLines(),
            )
        }

    fun testGroupsOfAnOrg() =
        runBlocking<Unit> {
            responseBody = """{"data": [{"id": "g-1", "attributes": {"name": "admins"}}, {"id": "g-2"}]}"""

            val groups = ContextApiClient().listGroups(client, "o-1").getOrThrow()

            assertEquals("request", listOf("GET /api/v3/groups?filter[org_id]=o-1"), requestLines())
            assertEquals(
                "groups, by ID where unnamed",
                listOf(NamedEntity("g-1", "admins"), NamedEntity("g-2", "g-2")),
                groups,
            )
        }

    fun testProjectsSearchedByName() =
        runBlocking<Unit> {
            responseBody = """{"data": [{"id": "p-1", "attributes": {"name": "app"}}], "page": {"next": "n-1"}}"""

            val page = ContextApiClient().searchProjects(client, "o-1", " ap ", "c-0").getOrThrow()

            val uri = requests.single().uri
            assertTrue("projects", uri.startsWith("/api/v3/projects?"))
            assertEquals(
                "params",
                setOf("filter[org_id]=o-1", "filter[name]=ap", "order_by=name", "page[limit]=50", "page[cursor]=c-0"),
                uri.substringAfter('?').split('&').toSet(),
            )
            assertEquals("projects", listOf(NamedEntity("p-1", "app")), page.items)
            assertEquals("next", "n-1", page.nextCursor)
        }

    fun testProjectsUnfilteredWithoutAName() =
        runBlocking<Unit> {
            responseBody = """{"data": []}"""

            ContextApiClient().searchProjects(client, "o-1", "", null).getOrThrow()

            assertFalse("no name filter", requests.single().uri.contains("filter[name]"))
        }

    fun testProjectBySlugWithItsOrganization() =
        runBlocking<Unit> {
            responseBody =
                """
            {"data": [{"id": "p-1", "attributes": {"name": "app"},
                       "references": {"org": {"id": "o-1", "attributes": {"name": "acme"}}}}]}
            """

            val info = RunApiClient().getProjectBySlug(client, "gh/acme/app").getOrThrow()

            assertEquals("request", listOf("GET /api/v3/projects?filter[slug]=gh/acme/app"), requestLines())
            assertEquals("project", ProjectInfo("p-1", "gh/acme/app", "app", Organization("o-1", "acme")), info)
        }

    fun testProjectNamedAfterItsSlugWhereUnnamed() =
        runBlocking<Unit> {
            responseBody = """{"data": [{"id": "p-1", "references": {"org": {"id": "o-1"}}}]}"""

            val info = RunApiClient().getProjectBySlug(client, "gh/acme/app").getOrThrow()

            assertEquals("project", ProjectInfo("p-1", "gh/acme/app", "app", Organization("o-1", "acme")), info)
        }

    fun testNoProjectForASlug() =
        runBlocking<Unit> {
            responseBody = """{"data": []}"""

            // The client logs the failure as an error, which fails a platform test unless expected.
            var result: Result<*>? = null
            LoggedErrorProcessor.executeAndReturnLoggedError {
                result = runBlocking { RunApiClient().getProjectBySlug(client, "gh/acme/app") }
            }

            assertTrue("fails", result!!.isFailure)
        }

    fun testProjectByIdHasAStandaloneSlug() =
        runBlocking<Unit> {
            responseBody =
                """
            {"data": {"id": "p-1", "attributes": {"name": "app"},
                      "references": {"org": {"id": "o-1", "attributes": {"name": "acme"}}}}}
            """

            val info = RunApiClient().getProjectById(client, "p-1").getOrThrow()

            assertEquals("request", listOf("GET /api/v3/projects/p-1"), requestLines())
            assertEquals("project", ProjectInfo("p-1", "circleci/o-1/p-1", "app", Organization("o-1", "acme")), info)
        }
}
