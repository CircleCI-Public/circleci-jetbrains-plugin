package com.circleci.idea.state

import com.circleci.idea.api.clients.V3Page
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PagedListTest {
    /** Pages by cursor, the first at null; a cursor in [held] waits for its deferred before answering. */
    private class Pages(var pages: Map<String?, V3Page<String>>) {
        val requested = mutableListOf<String?>()
        val held = mutableMapOf<String?, CompletableDeferred<Unit>>()
        var failing: String? = "none"

        val fetch: suspend (String?) -> Result<V3Page<String>> = { cursor ->
            requested.add(cursor)
            held[cursor]?.await()
            if (cursor == failing) Result.failure(Exception("boom")) else Result.success(pages.getValue(cursor))
        }
    }

    private fun threePages() =
        Pages(
            mapOf(
                null to V3Page(listOf("a", "b"), "p2"),
                "p2" to V3Page(listOf("c", "d"), "p3"),
                "p3" to V3Page(listOf("e"), null),
            ),
        )

    private fun list(pages: Pages) = PagedList<String, String>({ it }, pages.fetch)

    @Test
    fun testLoadsAPageAtATime() =
        runBlocking {
            val pages = threePages()
            val list = list(pages)

            assertEquals("the first page", listOf("a", "b"), list.reload().getOrThrow())
            assertTrue("more to come", list.state.value.hasMore)
            assertEquals("the next page's items", listOf("c", "d"), list.loadMore()!!.getOrThrow())
            assertEquals("the last", listOf("e"), list.loadMore()!!.getOrThrow())
            assertEquals("all listed", listOf("a", "b", "c", "d", "e"), list.state.value.items)
            assertFalse("no more", list.state.value.hasMore)
            assertNull("nothing past the last page", list.loadMore())
        }

    @Test
    fun testRefreshFetchesAsManyPagesAsListed() =
        runBlocking {
            val pages = threePages()
            val list = list(pages)
            list.reload()
            list.loadMore()
            pages.requested.clear()

            // A newer run pushed "b" onto the second page.
            pages.pages =
                mapOf(
                    null to V3Page(listOf("new", "a"), "q2"),
                    "q2" to V3Page(listOf("b", "c"), "q3"),
                )
            list.refresh()
            assertEquals("the first two pages", listOf(null, "q2"), pages.requested)
            assertEquals("the scrolled-to runs stay", listOf("new", "a", "b", "c"), list.state.value.items)
            assertTrue("and the cursor after them", list.state.value.hasMore)
        }

    @Test
    fun testPollFetchesOnlyTheFirstPage() =
        runBlocking {
            val pages = threePages()
            val list = list(pages)
            list.reload()
            list.loadMore()
            pages.requested.clear()

            // A newer run pushed "b" onto the second page; "a" changed.
            pages.pages = pages.pages + (null to V3Page(listOf("new", "a"), "q2"))
            assertEquals("merged", listOf("new", "a", "b", "c", "d"), list.poll().getOrThrow())
            assertEquals("the first page only", listOf<String?>(null), pages.requested)
            assertTrue("the cursor after what's listed stays", list.state.value.hasMore)
            assertEquals("which loads the next page", listOf("e"), list.loadMore()!!.getOrThrow())
        }

    @Test
    fun testPollDropsWhatLeftTheFirstPage() =
        runBlocking {
            val pages = threePages()
            val list = list(pages)
            list.reload()
            list.loadMore()

            // "a" no longer matches; "c" moved up.
            pages.pages = pages.pages + (null to V3Page(listOf("b", "c"), "p2"))
            assertEquals("a gone", listOf("b", "c", "d"), list.poll().getOrThrow())
        }

    @Test
    fun testPollOfAListNowOnOnePageListsJustThat() =
        runBlocking {
            val pages = threePages()
            val list = list(pages)
            list.reload()
            list.loadMore()

            pages.pages = mapOf(null to V3Page(listOf("a"), null))
            assertEquals("just the page", listOf("a"), list.poll().getOrThrow())
            assertFalse("nothing more", list.state.value.hasMore)
        }

    @Test
    fun testAFailedPollKeepsTheList() =
        runBlocking {
            val pages = threePages()
            val list = list(pages)
            list.reload()
            list.loadMore()
            pages.failing = null

            assertTrue("fails", list.poll().isFailure)
            assertEquals("kept", listOf("a", "b", "c", "d"), list.state.value.items)
            assertEquals("says why", "boom", list.state.value.error?.message)
        }

    @Test
    fun testListsItemsAPageSharesOnce() =
        runBlocking {
            val pages =
                Pages(mapOf(null to V3Page(listOf("a", "b"), "p2"), "p2" to V3Page(listOf("b", "c"), null)))
            val list = list(pages)
            list.reload()
            assertEquals("only what's new", listOf("c"), list.loadMore()!!.getOrThrow())
            assertEquals("each once", listOf("a", "b", "c"), list.state.value.items)
        }

    @Test
    fun testAFailedPageCanBeRetried() =
        runBlocking {
            val pages = threePages()
            val list = list(pages)
            list.reload()
            pages.failing = "p2"

            assertTrue("fails", list.loadMore()!!.isFailure)
            assertEquals("says why", "boom", list.state.value.error?.message)
            assertEquals("keeps what's listed", listOf("a", "b"), list.state.value.items)

            pages.failing = "none"
            assertEquals("loads on retrying", listOf("c", "d"), list.loadMore()!!.getOrThrow())
            assertNull("no error now", list.state.value.error)
        }

    @Test
    fun testAFailedRefreshKeepsTheListButAFailedReloadEmptiesIt() =
        runBlocking {
            val pages = threePages()
            val list = list(pages)
            list.reload()
            list.loadMore()
            pages.failing = "p2"

            assertTrue("refresh fails if any page does", list.refresh().isFailure)
            assertEquals("kept", listOf("a", "b", "c", "d"), list.state.value.items)

            pages.failing = null
            assertTrue("reload fails", list.reload().isFailure)
            assertEquals("emptied", emptyList<String>(), list.state.value.items)
            assertFalse("nothing more", list.state.value.hasMore)
        }

    @Test
    fun testARefreshSupersedesAPageLoading() =
        runBlocking {
            val pages = threePages()
            val list = list(pages)
            list.reload()
            pages.held["p2"] = CompletableDeferred()

            val more = async { list.loadMore() }
            yield()
            assertTrue("loading", list.state.value.loadingMore)
            list.refresh()
            assertFalse("not loading once replaced", list.state.value.loadingMore)

            pages.held.getValue("p2").complete(Unit)
            assertNull("its page is dropped", more.await())
            assertEquals("the refreshed list", listOf("a", "b"), list.state.value.items)
            assertEquals("and the next page can load", listOf("c", "d"), list.loadMore()!!.getOrThrow())
        }

    @Test
    fun testANewerRefreshSupersedesAnOlder() =
        runBlocking {
            val pages = threePages()
            val list = list(pages)
            pages.held[null] = CompletableDeferred()

            val older = async { list.reload() }
            yield()
            pages.held.remove(null)!!.also {
                pages.pages = mapOf(null to V3Page(listOf("newer"), null))
                list.reload()
                pages.pages = threePages().pages
                it.complete(Unit)
            }
            older.await()
            assertEquals("the newer's list", listOf("newer"), list.state.value.items)
        }
}
