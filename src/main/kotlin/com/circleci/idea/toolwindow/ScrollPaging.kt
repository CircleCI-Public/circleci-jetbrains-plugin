package com.circleci.idea.toolwindow

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.Flow

/*
 * Loading a list's next page as it's scrolled near its end, for the lists
 * that load a page at a time.
 */

/** How many rows from the end of a list the next page starts loading. */
private const val PREFETCH_DISTANCE = 5

/** Whether the list is scrolled to within a few rows of its end. */
internal fun LazyListState.isNearEnd(): Boolean {
    val layout = layoutInfo
    val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: return false
    return lastVisible >= layout.totalItemsCount - 1 - PREFETCH_DISTANCE
}

/**
 * Emits as the list scrolls or its length changes. A page that still leaves
 * the end in view changes the length, so the next page loads too.
 */
internal fun LazyListState.scrolledOrResized(): Flow<Pair<Int, Int>> =
    snapshotFlow {
        val layout = layoutInfo
        (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
    }
