package com.circleci.idea.api.clients

import com.circleci.idea.api.CircleCIApiClient
import com.circleci.idea.api.models.JobWire
import com.circleci.idea.api.models.ProjectWire
import com.circleci.idea.api.models.RunSearchPage
import com.circleci.idea.api.models.RunSearchRequest
import com.circleci.idea.api.models.RunSearchScope
import com.circleci.idea.api.models.RunWire
import com.circleci.idea.api.models.V3Entity
import com.circleci.idea.api.models.V3List
import com.circleci.idea.api.models.WorkflowWire
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.time.Instant
import java.time.temporal.ChronoUnit

/** One page of a V3 list, and the cursor for the next (null on the last page). */
data class V3Page<T>(
    val items: List<T>,
    val nextCursor: String?,
)

/**
 * API client for the V3 runs, workflows and jobs endpoints.
 */
class RunApiClient : CircleCIApiClientBase() {
    /**
     * Search a project's runs, newest first, via POST /api/v3/runs/search.
     *
     * @param filter A runs/search filter expression, e.g. `pipeline.git.branch == "main"`; empty for none
     * @param limit Page size, at most 20
     */
    @Suppress("LongParameterList")
    fun searchRuns(
        client: CircleCIApiClient,
        projectId: String,
        from: Instant,
        to: Instant,
        filter: String,
        limit: Int,
        cursor: String? = null,
    ): Result<V3Page<RunWire>> {
        val body =
            RunSearchRequest(
                scope = RunSearchScope(listOf(projectId), rfc3339(from), rfc3339(to)),
                filter = filter,
                page = RunSearchPage(cursor.orEmpty(), limit),
            )
        return executeRequest(client, "/api/v3/runs/search", body = body) { parsePage<RunWire>(it) }
    }

    /**
     * List the authenticated user's runs across all projects, newest first,
     * via GET /api/v3/runs?filter[user_id]=me. Null filters are omitted.
     */
    @Suppress("LongParameterList")
    fun listMyRuns(
        client: CircleCIApiClient,
        phase: String?,
        currentOutcome: String?,
        from: Instant?,
        to: Instant?,
        limit: Int,
        cursor: String? = null,
    ): Result<V3Page<RunWire>> {
        val params =
            buildMap {
                put("filter[user_id]", "me")
                phase?.let { put("filter[phase]", it) }
                currentOutcome?.let { put("filter[current_outcome]", it) }
                from?.let { put("filter[from]", rfc3339(it)) }
                to?.let { put("filter[to]", rfc3339(it)) }
                put("page[limit]", limit.toString())
                cursor?.let { put("page[cursor]", it) }
            }
        return executeRequest(client, "/api/v3/runs", params) { parsePage<RunWire>(it) }
    }

    /** All workflows of a run. */
    fun getRunWorkflows(
        client: CircleCIApiClient,
        runId: String,
    ): Result<List<WorkflowWire>> {
        return fetchAllPages { cursor ->
            executeRequest(client, "/api/v3/workflows", pageParams("filter[run_id]" to runId, cursor)) {
                parsePage<WorkflowWire>(it)
            }
        }
    }

    /** All jobs of a workflow. */
    fun getWorkflowJobs(
        client: CircleCIApiClient,
        workflowId: String,
    ): Result<List<JobWire>> {
        return fetchAllPages { cursor ->
            executeRequest(client, "/api/v3/jobs", pageParams("filter[workflow_id]" to workflowId, cursor)) {
                parsePage<JobWire>(it)
            }
        }
    }

    /** Look up a project by slug (e.g. "gh/org/repo"); fails if there's no such project. */
    fun getProjectBySlug(
        client: CircleCIApiClient,
        slug: String,
    ): Result<ProjectWire> {
        return executeRequest(client, "/api/v3/projects", mapOf("filter[slug]" to slug)) { data ->
            parsePage<ProjectWire>(data).items.firstOrNull() ?: error("No CircleCI project found for $slug")
        }
    }

    /** Look up a project by ID. */
    fun getProjectById(
        client: CircleCIApiClient,
        projectId: String,
    ): Result<ProjectWire> {
        return executeRequest(client, "/api/v3/projects/$projectId") { data ->
            gson.fromJson<V3Entity<ProjectWire>>(data, object : TypeToken<V3Entity<ProjectWire>>() {}.type).data
                ?: error("No CircleCI project found for $projectId")
        }
    }

    private inline fun <reified T> parsePage(data: JsonObject): V3Page<T> {
        val list: V3List<T> = gson.fromJson(data, object : TypeToken<V3List<T>>() {}.type)
        return V3Page(list.data.orEmpty(), list.page?.next?.takeIf { it.isNotEmpty() })
    }

    private fun pageParams(
        filter: Pair<String, String>,
        cursor: String?,
    ): Map<String, String> {
        return buildMap {
            put(filter.first, filter.second)
            cursor?.let { put("page[cursor]", it) }
        }
    }

    private fun <T> fetchAllPages(fetch: (String?) -> Result<V3Page<T>>): Result<List<T>> {
        val items = mutableListOf<T>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val page = fetch(cursor).getOrElse { return Result.failure(it) }
            items.addAll(page.items)
            cursor = page.nextCursor ?: return Result.success(items)
        }
        logger.warn("Stopped after $MAX_PAGES pages")
        return Result.success(items)
    }

    private fun rfc3339(instant: Instant): String = instant.truncatedTo(ChronoUnit.SECONDS).toString()

    private companion object {
        // A guard against a server that never stops handing out cursors.
        const val MAX_PAGES = 20
    }
}
