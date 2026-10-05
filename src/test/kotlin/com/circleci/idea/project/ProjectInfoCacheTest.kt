package com.circleci.idea.project

import com.circleci.idea.project.models.Organization
import com.circleci.idea.project.models.ProjectInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectInfoCacheTest {
    private val acme = Organization("o-1", "acme")
    private val app = ProjectInfo("p-1", "gh/acme/app", "app", acme)

    /** The API's projects, counting the lookups; a lookup waits for [held], if set, before answering. */
    private class Api(val projects: List<ProjectInfo>) {
        val lookups = mutableListOf<String>()
        var held: CompletableDeferred<Unit>? = null
        var failing = false

        val bySlug: suspend (String) -> Result<ProjectInfo> = { slug -> answer(slug) { it.slug == slug } }

        // The API doesn't say a project's slug, so a project looked up by ID has a standalone one.
        val byId: suspend (String) -> Result<ProjectInfo> = { id ->
            answer(id) { it.id == id }.map { it.copy(slug = "circleci/${it.org.id}/${it.id}") }
        }

        private suspend fun answer(
            key: String,
            match: (ProjectInfo) -> Boolean,
        ): Result<ProjectInfo> {
            lookups += key
            held?.await()
            if (failing) return Result.failure(Exception("boom"))
            return projects.firstOrNull(match)?.let { Result.success(it) } ?: Result.failure(Exception("no project"))
        }
    }

    private fun CoroutineScope.cache(api: Api) = ProjectInfoCache(this, api.bySlug, api.byId)

    @Test
    fun testLooksAProjectUpOnce() =
        runBlocking {
            val api = Api(listOf(app))
            val cache = cache(api)

            assertEquals("the project", app, cache.bySlug("gh/acme/app").getOrThrow())
            assertEquals("again", app, cache.bySlug("gh/acme/app").getOrThrow())
            assertEquals("looked up once", listOf("gh/acme/app"), api.lookups)
        }

    @Test
    fun testLookupsAtOnceShareOne() =
        runBlocking {
            val api = Api(listOf(app)).apply { held = CompletableDeferred() }
            val cache = cache(api)

            val first = async { cache.bySlug("gh/acme/app") }
            val second = async { cache.bySlug("gh/acme/app") }
            yield()
            api.held!!.complete(Unit)

            assertEquals("the first", app, first.await().getOrThrow())
            assertEquals("the second", app, second.await().getOrThrow())
            assertEquals("looked up once", listOf("gh/acme/app"), api.lookups)
        }

    @Test
    fun testByIdKeepsTheSlugLookedUpBy() =
        runBlocking {
            val api = Api(listOf(app))
            val cache = cache(api)

            cache.bySlug("gh/acme/app").getOrThrow()

            assertEquals("the slug looked up by", app, cache.byId("p-1").getOrThrow())
            assertEquals("not looked up again", listOf("gh/acme/app"), api.lookups)
        }

    @Test
    fun testByIdAloneGivesAStandaloneSlug() =
        runBlocking {
            val api = Api(listOf(app))
            val cache = cache(api)

            assertEquals("standalone slug", "circleci/o-1/p-1", cache.byId("p-1").getOrThrow().slug)
            assertEquals("remembered", "circleci/o-1/p-1", cache.byId("p-1").getOrThrow().slug)
            assertEquals("looked up once", listOf("p-1"), api.lookups)
        }

    @Test
    fun testASlugLookupCorrectsAStandaloneSlug() =
        runBlocking {
            val api = Api(listOf(app))
            val cache = cache(api)

            cache.byId("p-1").getOrThrow()
            cache.bySlug("gh/acme/app").getOrThrow()

            assertEquals("the slug looked up by", "gh/acme/app", cache.byId("p-1").getOrThrow().slug)
        }

    @Test
    fun testFailureIsTriedAgain() =
        runBlocking {
            val api = Api(listOf(app)).apply { failing = true }
            val cache = cache(api)

            assertTrue("fails", cache.bySlug("gh/acme/app").isFailure)
            api.failing = false

            assertEquals("works next time", app, cache.bySlug("gh/acme/app").getOrThrow())
            assertEquals("looked up twice", listOf("gh/acme/app", "gh/acme/app"), api.lookups)
        }

    @Test
    fun testClearForgetsProjects() =
        runBlocking {
            val api = Api(listOf(app))
            val cache = cache(api)

            cache.bySlug("gh/acme/app").getOrThrow()
            cache.clear()
            cache.bySlug("gh/acme/app").getOrThrow()

            assertEquals("looked up again", listOf("gh/acme/app", "gh/acme/app"), api.lookups)
        }
}
