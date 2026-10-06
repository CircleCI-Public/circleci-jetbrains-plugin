package com.circleci.idea.toolwindow.tree

import androidx.compose.runtime.snapshots.Snapshot
import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.run.RunScope
import com.circleci.idea.settings.CircleCISettings
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.state.FiltersState
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The run tree's model against a local HTTP server answering V3 JSON: what
 * the tree shows, and which requests it sends, across loads, refreshes and
 * polls. It lists "My runs", so needs no project selected.
 *
 * The model runs on the EDT, so the tests run off it and wait for the model
 * to settle: [CircleCITreeModel.pollRuns] for the run list, then its loading
 * flag for the levels refreshed below it.
 */
class CircleCITreeModelTest : BasePlatformTestCase() {
    private lateinit var server: HttpServer

    // What the fake API answers, changed by the tests as runs go on; read on the server's thread.
    @Volatile private var runs = listOf<String>()
    private val workflows = ConcurrentHashMap<String, List<String>>()
    private val jobs = ConcurrentHashMap<String, List<String>>()

    // The paths answered with a server error instead.
    private val failing = ConcurrentHashMap.newKeySet<String>()

    // Each request's path and decoded query.
    private val requests = CopyOnWriteArrayList<String>()

    private lateinit var scope: CoroutineScope
    private lateinit var model: CircleCITreeModel

    private lateinit var previousFilters: FiltersState
    private lateinit var previousHostUrl: String
    private lateinit var previousAuthMethod: String

    // The model's scope runs on the EDT; the tests wait for it from their own thread.
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> answer(exchange) }
        server.start()

        val settings = CircleCISettings.getInstance()
        previousHostUrl = settings.hostUrl
        previousAuthMethod = settings.authMethod
        val stateStore = CircleCIStateStore.getInstance(project)
        previousFilters = stateStore.filters.value
        stateStore.updateFilters { FiltersState(scope = RunScope.MY_RUNS) }

        val url = "http://127.0.0.1:${server.address.port}"
        runBlocking { CircleCIAuthService.getInstance(project).login("token", url).getOrThrow() }
        requests.clear()
    }

    override fun tearDown() {
        try {
            if (::scope.isInitialized) scope.cancel()
            CircleCIAuthService.logOutEverywhere()
            CircleCISettings.getInstance().hostUrl = previousHostUrl
            CircleCISettings.getInstance().authMethod = previousAuthMethod
            CircleCIStateStore.getInstance(project).updateFilters { previousFilters }
            server.stop(0)
        } finally {
            super.tearDown()
        }
    }

    private fun answer(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query =
            exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                val (name, value) = it.split('=', limit = 2) + ""
                URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
            }
        requests += path + query.entries.joinToString("&", prefix = "?") { "${it.key}=${it.value}" }
        val (status, body) =
            when {
                path in failing -> 500 to """{"message": "Internal Server Error"}"""
                path == "/api/v3/users" -> 200 to """{"data": [{"id": "u-1", "attributes": {"login": "ada"}}]}"""
                path == "/api/v3/runs" -> 200 to page(runs)
                path == "/api/v3/workflows" -> 200 to page(workflows[query["filter[run_id]"]].orEmpty())
                path == "/api/v3/jobs" -> 200 to page(jobs[query["filter[workflow_id]"]].orEmpty())
                else -> 404 to """{"message": "Not Found"}"""
            }
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun page(items: List<String>) = """{"data": [${items.joinToString(",")}], "page": {}}"""

    private fun run(
        id: String,
        phase: String,
        outcome: String? = null,
    ) = """{"id": "$id", "attributes": {"number": 1, "phase": "$phase", "outcome": ${quoted(outcome)}}}"""

    private fun workflow(
        id: String,
        phase: String,
        outcome: String? = null,
    ) = """{"id": "$id", "attributes": {"name": "$id", "phase": "$phase", "outcome": ${quoted(outcome)}}}"""

    private fun job(
        id: String,
        phase: String,
        outcome: String? = null,
    ) = """
        {"id": "$id", "attributes": {"name": "$id", "type": "build", "phase": "$phase",
         "outcome": ${quoted(outcome)}, "started_at": "2026-10-06T12:00:00Z"}}
    """

    private fun quoted(value: String?) = value?.let { "\"$it\"" } ?: "null"

    private fun workflowFetches(runId: String) = requests.count { it == "/api/v3/workflows?filter[run_id]=$runId" }

    private fun jobFetches(workflowId: String) = requests.count { it == "/api/v3/jobs?filter[workflow_id]=$workflowId" }

    private fun runFetches() = requests.count { it.startsWith("/api/v3/runs?") }

    private suspend fun <T> onEdt(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    /** Wait, up to a timeout, for [condition] to hold on the EDT. */
    private suspend fun await(
        what: String,
        condition: () -> Boolean,
    ) {
        try {
            withTimeout(TIMEOUT_MS) {
                while (!onEdt(condition)) delay(POLL_MS)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $what; the tree shows ${onEdt(::shown)}", e)
        }
    }

    /** Wait for every load and refresh in flight to end. */
    private suspend fun awaitIdle() = await("loads to end") { !model.loading.value }

    /** Create the model, which lists the runs, and wait for them. */
    private suspend fun load() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        model = onEdt { CircleCITreeModel(project, scope) }
        await("the run list") { model.root.childrenLoaded && !model.loading.value }
    }

    /** Open [key] in the tree, and wait for its children to load. */
    private suspend fun open(key: String) {
        setOpen(key, true)
        await("$key to load") { findNode(model.root, key)?.let { it.childrenLoaded || it.isErrorShown() } == true }
        awaitIdle()
    }

    private fun CircleCITreeNode.isErrorShown() = children(this).any { it is ErrorNode }

    private suspend fun close(key: String) = setOpen(key, false)

    private suspend fun setOpen(
        key: String,
        open: Boolean,
    ) = onEdt {
        val treeState = model.treeState
        treeState.openNodes = if (open) treeState.openNodes + key else treeState.openNodes - key
        // Nothing composes in a test to tell the model's snapshotFlow of what's open.
        Snapshot.sendApplyNotifications()
    }

    /** Poll as the polling service does, and wait for the levels it refreshes below the runs. */
    private suspend fun poll(): Result<*>? {
        val result = model.pollRuns()
        awaitIdle()
        return result
    }

    /** The tree as it shows: a line a row, indented by depth, each a run, workflow or job by key and status. */
    private fun shown(): List<String> {
        val lines = mutableListOf<String>()

        fun add(
            node: CircleCITreeNode,
            depth: Int,
        ) {
            val row =
                when (node) {
                    is RunNode, is WorkflowNode, is JobNode -> "${keyOf(node)} ${node.getStatus()}"
                    else -> node.getDisplayText()
                }
            lines += "  ".repeat(depth) + row
            children(node).forEach { add(it, depth + 1) }
        }
        children(model.root).forEach { add(it, 0) }
        return lines
    }

    private suspend fun assertShown(
        message: String,
        expected: List<String>,
    ) = assertEquals(message, expected, onEdt(::shown))

    fun testEndedRunsWorkflowsAreFetchedAgainUntilTheyHaveEndedToo() =
        runBlocking<Unit> {
            // The run listing says the run ended before the workflows endpoint says its workflow did.
            runs = listOf(run("r-1", "ended", "succeeded"))
            workflows["r-1"] = listOf(workflow("w-1", "started"))
            load()
            open("run:r-1")
            assertShown("the workflow running", listOf("run:r-1 SUCCESS", "  workflow:w-1 RUNNING"))

            poll()
            assertEquals("fetched again while it's running", 2, workflowFetches("r-1"))

            workflows["r-1"] = listOf(workflow("w-1", "ended", "succeeded"))
            poll()
            assertEquals("fetched again", 3, workflowFetches("r-1"))
            assertShown("the workflow ended", listOf("run:r-1 SUCCESS", "  workflow:w-1 SUCCESS"))

            poll()
            poll()
            assertEquals("not fetched once both have ended", 3, workflowFetches("r-1"))
            assertEquals("the runs were polled", 5, runFetches())
            assertShown("still ended", listOf("run:r-1 SUCCESS", "  workflow:w-1 SUCCESS"))
        }

    fun testEndedWorkflowsJobsAreFetchedAgainUntilTheyHaveEndedToo() =
        runBlocking<Unit> {
            // The workflow says it ended before the jobs endpoint says its job did.
            runs = listOf(run("r-1", "started"))
            workflows["r-1"] = listOf(workflow("w-1", "ended", "succeeded"), workflow("w-2", "started"))
            jobs["w-1"] = listOf(job("j-1", "started"))
            load()
            open("run:r-1")
            open("workflow:w-1")
            assertShown(
                "the job running",
                listOf("run:r-1 RUNNING", "  workflow:w-1 SUCCESS", "    job:j-1 RUNNING", "  workflow:w-2 RUNNING"),
            )

            poll()
            assertEquals("fetched again while it's running", 2, jobFetches("w-1"))

            jobs["w-1"] = listOf(job("j-1", "ended", "succeeded"))
            poll()
            assertEquals("fetched again", 3, jobFetches("w-1"))
            assertShown(
                "the job ended",
                listOf("run:r-1 RUNNING", "  workflow:w-1 SUCCESS", "    job:j-1 SUCCESS", "  workflow:w-2 RUNNING"),
            )

            poll()
            assertEquals("not fetched once both have ended", 3, jobFetches("w-1"))
            assertEquals("the running run's workflows still are", 4, workflowFetches("r-1"))
        }

    fun testRunningRunsOpenWorkflowsAreFetchedOnEachPoll() =
        runBlocking<Unit> {
            runs = listOf(run("r-1", "started"))
            workflows["r-1"] = listOf(workflow("w-1", "started"))
            load()
            assertEquals("not fetched until opened", 0, workflowFetches("r-1"))
            open("run:r-1")
            assertEquals("fetched on opening", 1, workflowFetches("r-1"))

            poll()
            poll()
            assertEquals("fetched on each poll", 3, workflowFetches("r-1"))

            workflows["r-1"] = listOf(workflow("w-1", "started", null), workflow("w-2", "started"))
            poll()
            assertShown(
                "a workflow added",
                listOf("run:r-1 RUNNING", "  workflow:w-1 RUNNING", "  workflow:w-2 RUNNING"),
            )
        }

    fun testClosedRunsWorkflowsAreDroppedAndLoadAgainOnOpening() =
        runBlocking<Unit> {
            runs = listOf(run("r-1", "started"))
            workflows["r-1"] = listOf(workflow("w-1", "started"))
            load()
            open("run:r-1")
            close("run:r-1")

            poll()
            assertEquals("not fetched while closed", 1, workflowFetches("r-1"))
            assertShown("dropped", listOf("run:r-1 RUNNING"))
            assertFalse("to load again", onEdt { findNode(model.root, "run:r-1")!!.childrenLoaded })

            workflows["r-1"] = listOf(workflow("w-1", "ended", "failed"))
            open("run:r-1")
            assertEquals("fetched on opening", 2, workflowFetches("r-1"))
            assertShown("as they are now", listOf("run:r-1 RUNNING", "  workflow:w-1 FAILED"))
        }

    fun testEndedRunsWorkflowsAreKeptWhenClosedOnceAllHaveEnded() =
        runBlocking<Unit> {
            runs = listOf(run("r-1", "ended", "failed"))
            workflows["r-1"] = listOf(workflow("w-1", "ended", "failed"))
            load()
            open("run:r-1")
            close("run:r-1")

            poll()
            open("run:r-1")

            assertEquals("fetched just the once", 1, workflowFetches("r-1"))
            assertShown("kept", listOf("run:r-1 FAILED", "  workflow:w-1 FAILED"))
        }

    fun testEndedRunThatStartsAgainHasItsWorkflowsFetchedAgain() =
        runBlocking<Unit> {
            runs = listOf(run("r-1", "ended", "failed"))
            workflows["r-1"] = listOf(workflow("w-1", "ended", "failed"))
            load()
            open("run:r-1")
            poll()
            assertEquals("kept while ended", 1, workflowFetches("r-1"))

            // Rerunning the workflow starts the run again, with a new workflow.
            runs = listOf(run("r-1", "started"))
            workflows["r-1"] = listOf(workflow("w-1", "ended", "failed"), workflow("w-2", "started"))
            poll()

            assertEquals("fetched again", 2, workflowFetches("r-1"))
            assertShown(
                "the rerun",
                listOf("run:r-1 RUNNING", "  workflow:w-1 FAILED", "  workflow:w-2 RUNNING"),
            )
        }

    fun testFailedRefreshKeepsShowingWhatLoadedLast() =
        runBlocking<Unit> {
            runs = listOf(run("r-1", "started"))
            workflows["r-1"] = listOf(workflow("w-1", "started"))
            load()
            open("run:r-1")
            val loaded = listOf("run:r-1 RUNNING", "  workflow:w-1 RUNNING")

            runs = listOf(run("r-1", "ended", "succeeded"))
            workflows["r-1"] = listOf(workflow("w-1", "ended", "succeeded"))
            failing += "/api/v3/runs"
            val result = poll()
            assertTrue("the poll failed: $result", result?.isFailure == true)
            assertEquals("the workflows not refreshed below it", 1, workflowFetches("r-1"))
            assertShown("the runs as they loaded", loaded)

            failing.clear()
            failing += "/api/v3/workflows"
            poll()
            assertEquals("the workflows tried", 2, workflowFetches("r-1"))
            assertShown("the workflows as they loaded", listOf("run:r-1 SUCCESS", "  workflow:w-1 RUNNING"))

            failing.clear()
            poll()
            assertShown("refreshed once it can", listOf("run:r-1 SUCCESS", "  workflow:w-1 SUCCESS"))
        }

    fun testFailedLoadIsTriedAgainOnReopening() =
        runBlocking<Unit> {
            runs = listOf(run("r-1", "started"))
            workflows["r-1"] = listOf(workflow("w-1", "started"))
            failing += "/api/v3/workflows"
            load()
            open("run:r-1")
            val error = onEdt { children(findNode(model.root, "run:r-1")!!).single().getDisplayText() }
            assertTrue("the error shown: $error", error.startsWith("Error: "))

            poll()
            assertEquals("not tried again while open", 1, workflowFetches("r-1"))

            failing.clear()
            close("run:r-1")
            await("the error to go") { findNode(model.root, "run:r-1")!!.childCount == 0 }
            open("run:r-1")

            assertEquals("tried again", 2, workflowFetches("r-1"))
            assertShown("loaded", listOf("run:r-1 RUNNING", "  workflow:w-1 RUNNING"))
        }

    fun testPollKeepsTheNodesOfRunsAndWorkflowsStillListed() =
        runBlocking<Unit> {
            runs = listOf(run("r-1", "started"), run("r-2", "ended", "succeeded"))
            workflows["r-1"] = listOf(workflow("w-1", "started"))
            load()
            open("run:r-1")
            val (runNode, workflowNode) =
                onEdt {
                    findNode(
                        model.root,
                        "run:r-1",
                    ) to findNode(model.root, "workflow:w-1")
                }

            runs = listOf(run("r-3", "started"), run("r-1", "started"))
            poll()

            assertShown(
                "a new run on top, and one gone",
                listOf("run:r-3 RUNNING", "run:r-1 RUNNING", "  workflow:w-1 RUNNING"),
            )
            assertSame("the run's node kept", runNode, onEdt { findNode(model.root, "run:r-1") })
            assertSame("the workflow's node kept", workflowNode, onEdt { findNode(model.root, "workflow:w-1") })
            assertTrue("still open", onEdt { "run:r-1" in model.treeState.openNodes })
            assertEquals("the new run's not opened", 0, workflowFetches("r-3"))
        }

    fun testNoRunsSaysSo() =
        runBlocking<Unit> {
            load()
            assertShown("the empty message", listOf("No runs found"))
            assertEquals(
                "the user's own runs listed",
                listOf("/api/v3/runs?filter[user_id]=me&page[limit]=20"),
                requests,
            )
        }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
