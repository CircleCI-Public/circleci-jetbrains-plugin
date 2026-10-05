package com.circleci.idea.state

import com.circleci.idea.api.clients.V3Page
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A list fetched a page at a time, for a view that loads the next page as
 * it's scrolled to.
 *
 * [reload] lists the first page afresh; [loadMore] adds the next; [refresh]
 * fetches as many pages as are listed again, so what's been scrolled to
 * stays. Items a page shares with those listed (as newer ones push the list
 * along) are listed once.
 *
 * A [reload] or [refresh] supersedes a [loadMore] in flight, whose page is
 * then dropped, and a newer [reload] or [refresh] supersedes an older one.
 * Calls are expected on one thread (the EDT), interleaving only as they suspend.
 *
 * @param key What identifies an item, to list it once
 * @param fetch Fetches the page at a cursor, the first at null
 */
class PagedList<T, K>(
    private val key: (T) -> K,
    private val fetch: suspend (cursor: String?) -> Result<V3Page<T>>,
) {
    /** @property error Why the last load failed, until one succeeds */
    data class State<T>(
        val items: List<T> = emptyList(),
        val hasMore: Boolean = false,
        val loadingMore: Boolean = false,
        val error: Throwable? = null,
    )

    private val _state = MutableStateFlow(State<T>())
    val state: StateFlow<State<T>> = _state.asStateFlow()

    private var cursor: String? = null
    private var pages = 0

    // Bumped as a reload or refresh starts and ends, so what was in flight can tell it was superseded.
    private var generation = 0

    // The generation a loadMore in flight started in, for one at a time in each.
    private var loadingMoreIn: Int? = null

    /** List the first page afresh. A failure empties the list. */
    suspend fun reload(): Result<List<T>> = replace(1, keepOnFailure = false)

    /** Fetch as many pages as are listed again, or the first if none are. A failure keeps what's listed. */
    suspend fun refresh(): Result<List<T>> = replace(maxOf(pages, 1), keepOnFailure = true)

    /**
     * Load the next page, adding its items to the list.
     *
     * @return The items it added, or a failure; null when there's no next
     *   page, it's loading already, or a reload or refresh superseded it
     */
    suspend fun loadMore(): Result<List<T>>? {
        val next = cursor
        val started = generation
        if (next == null || loadingMoreIn == started) return null
        loadingMoreIn = started
        _state.value = _state.value.copy(loadingMore = true, error = null)

        val result = fetch(next)
        if (started != generation) return null
        loadingMoreIn = null
        return result.fold(
            onSuccess = { page ->
                val listed = _state.value.items.map(key).toSet()
                val added = page.items.distinctBy(key).filter { key(it) !in listed }
                cursor = page.nextCursor
                pages++
                _state.value = State(_state.value.items + added, hasMore = cursor != null)
                Result.success(added)
            },
            onFailure = { error ->
                _state.value = _state.value.copy(loadingMore = false, error = error)
                Result.failure(error)
            },
        )
    }

    /**
     * Fetch up to [count] pages from the first and list them in place of
     * what's listed, if no newer reload or refresh has started meanwhile.
     */
    private suspend fun replace(
        count: Int,
        keepOnFailure: Boolean,
    ): Result<List<T>> {
        val started = ++generation
        val items = mutableListOf<T>()
        var next: String? = null
        var fetched = 0
        var failure: Throwable? = null
        do {
            fetch(next).fold(
                onSuccess = { page ->
                    items.addAll(page.items)
                    next = page.nextCursor
                    fetched++
                },
                onFailure = { failure = it },
            )
        } while (failure == null && fetched < count && next != null)

        if (started != generation) return failure?.let { Result.failure(it) } ?: Result.success(items)
        // Supersede a loadMore that started while this was fetching: it was for the list replaced.
        generation++
        loadingMoreIn = null

        failure?.let { error ->
            _state.value =
                if (keepOnFailure) {
                    _state.value.copy(loadingMore = false, error = error)
                } else {
                    cursor = null
                    pages = 0
                    State(error = error)
                }
            return Result.failure(error)
        }
        val listed = items.distinctBy(key)
        cursor = next
        pages = fetched
        _state.value = State(listed, hasMore = cursor != null)
        return Result.success(listed)
    }
}
