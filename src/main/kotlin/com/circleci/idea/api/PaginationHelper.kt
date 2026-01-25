package com.circleci.idea.api

import com.circleci.idea.api.models.PaginatedResponse
import com.circleci.idea.logging.CircleCILogger

/**
 * Helper for handling paginated API requests.
 */
object PaginationHelper {

    private val logger = CircleCILogger.getInstance()

    /**
     * Fetch all pages of a paginated resource.
     *
     * @param maxPages Maximum number of pages to fetch (prevents infinite loops)
     * @param fetcher Function that fetches a page given a page token
     * @return All items from all pages
     */
    suspend fun <T> fetchAllPages(
        maxPages: Int = 100,
        fetcher: suspend (pageToken: String?) -> Result<PaginatedResponse<T>>
    ): List<T> {
        val allItems = mutableListOf<T>()
        var pageToken: String? = null
        var pageCount = 0
        var shouldContinue = true

        logger.debug("Fetching all pages (max: $maxPages)")

        while (pageCount < maxPages && shouldContinue) {
            val result = fetcher(pageToken)

            if (result.isSuccess) {
                val response = result.getOrNull()!!
                allItems.addAll(response.items)
                pageToken = response.nextPageToken

                pageCount++
                logger.debug("Fetched page $pageCount with ${response.items.size} items")

                // If no next page token, we're done
                if (pageToken == null) {
                    shouldContinue = false
                }
            } else {
                logger.error("Failed to fetch page ${pageCount + 1}: ${result.exceptionOrNull()?.message}")
                shouldContinue = false
            }
        }

        if (pageCount >= maxPages) {
            logger.warn("Reached maximum page limit ($maxPages)")
        }

        logger.info("Fetched total of ${allItems.size} items across $pageCount pages")
        return allItems
    }

    /**
     * Fetch a specific number of pages.
     *
     * @param depth Number of pages to fetch
     * @param fetcher Function that fetches a page given a page token
     * @return Items from fetched pages and the next page token
     */
    suspend fun <T> fetchMultiplePages(
        depth: Int,
        fetcher: suspend (pageToken: String?) -> Result<PaginatedResponse<T>>
    ): Pair<List<T>, String?> {
        val allItems = mutableListOf<T>()
        var pageToken: String? = null
        var shouldContinue = true

        logger.debug("Fetching $depth pages")

        var pageIndex = 0
        while (pageIndex < depth && shouldContinue) {
            val result = fetcher(pageToken)

            if (result.isSuccess) {
                val response = result.getOrNull()!!
                allItems.addAll(response.items)
                pageToken = response.nextPageToken

                logger.debug("Fetched page ${pageIndex + 1}/${depth} with ${response.items.size} items")

                // If no next page token, stop early
                if (pageToken == null) {
                    logger.debug("No more pages available")
                    shouldContinue = false
                }
            } else {
                logger.error("Failed to fetch page ${pageIndex + 1}: ${result.exceptionOrNull()?.message}")
                shouldContinue = false
            }

            pageIndex++
        }

        logger.info("Fetched ${allItems.size} items from $pageIndex pages")
        return Pair(allItems, pageToken)
    }

    /**
     * Fetch a single page.
     *
     * @param pageToken Page token for the page to fetch
     * @param fetcher Function that fetches a page given a page token
     * @return Items from the page and the next page token
     */
    suspend fun <T> fetchPage(
        pageToken: String? = null,
        fetcher: suspend (pageToken: String?) -> Result<PaginatedResponse<T>>
    ): Pair<List<T>, String?> {
        logger.debug("Fetching single page" + if (pageToken != null) " (token: ${pageToken.take(10)}...)" else "")

        val result = fetcher(pageToken)

        return result.fold(
            onSuccess = { response ->
                logger.debug("Fetched page with ${response.items.size} items")
                Pair(response.items, response.nextPageToken)
            },
            onFailure = { error ->
                logger.error("Failed to fetch page: ${error.message}", error)
                Pair(emptyList(), null)
            }
        )
    }
}
