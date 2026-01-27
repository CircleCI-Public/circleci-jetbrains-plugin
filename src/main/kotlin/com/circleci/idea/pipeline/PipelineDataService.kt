package com.circleci.idea.pipeline

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.api.PaginationHelper
import com.circleci.idea.api.ResponseCache
import com.circleci.idea.api.models.PipelineInfo
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.state.Pipeline
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Service for managing pipeline data.
 * Handles fetching, caching, and state management for pipelines.
 */
@Service(Service.Level.PROJECT)
class PipelineDataService(private val project: Project) {
    private val logger = CircleCILogger.getInstance()
    private val stateStore = project.getService(CircleCIStateStore::class.java)
    private val apiService = CircleCIApiService.getInstance()

    // Cache for pipeline responses (30 second TTL)
    private val pipelineCache = ResponseCache<String, List<PipelineInfo>>(30_000)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        logger.logLifecycleEvent("PipelineDataService initialized")
    }

    /**
     * Fetch pipelines for a project.
     *
     * @param projectSlug Project slug (e.g., "gh/org/repo")
     * @param branch Optional branch filter
     * @param myPipelinesOnly If true, fetch only user's pipelines
     * @param useCache If true, use cached data if available
     * @return List of pipelines
     */
    suspend fun fetchPipelines(
        projectSlug: String,
        branch: String? = null,
        myPipelinesOnly: Boolean = false,
        useCache: Boolean = true,
    ): List<Pipeline> {
        logger.info("Fetching pipelines for $projectSlug (branch: ${branch ?: "all"}, mine: $myPipelinesOnly)")
        _isLoading.value = true

        try {
            val cacheKey = buildCacheKey(projectSlug, branch, myPipelinesOnly)

            val pipelineInfos =
                if (useCache) {
                    pipelineCache.getOrPut(cacheKey) {
                        fetchPipelinesFromApi(projectSlug, branch, myPipelinesOnly, pageDepth = 1)
                    }
                } else {
                    pipelineCache.invalidate(cacheKey)
                    fetchPipelinesFromApi(projectSlug, branch, myPipelinesOnly, pageDepth = 1)
                }

            // Convert to domain model
            val pipelines = pipelineInfos.map { convertToPipeline(it) }

            // Update state store
            stateStore.setPipelines(projectSlug, pipelines)

            logger.info("Fetched ${pipelines.size} pipelines for $projectSlug")
            return pipelines
        } catch (e: Exception) {
            logger.error("Failed to fetch pipelines for $projectSlug", e)
            return emptyList()
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Fetch all pipelines (all pages).
     *
     * @param projectSlug Project slug
     * @param branch Optional branch filter
     * @param myPipelinesOnly If true, fetch only user's pipelines
     * @return List of all pipelines
     */
    suspend fun fetchAllPipelines(
        projectSlug: String,
        branch: String? = null,
        myPipelinesOnly: Boolean = false,
    ): List<Pipeline> {
        logger.info("Fetching ALL pipelines for $projectSlug")
        _isLoading.value = true

        try {
            val pipelineInfos =
                fetchPipelinesFromApi(
                    projectSlug,
                    branch,
                    myPipelinesOnly,
                    fetchAll = true,
                )

            val pipelines = pipelineInfos.map { convertToPipeline(it) }
            stateStore.setPipelines(projectSlug, pipelines)

            logger.info("Fetched ${pipelines.size} total pipelines for $projectSlug")
            return pipelines
        } catch (e: Exception) {
            logger.error("Failed to fetch all pipelines for $projectSlug", e)
            return emptyList()
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Load more pipelines (next page).
     *
     * @param projectSlug Project slug
     * @param branch Optional branch filter
     * @param myPipelinesOnly If true, fetch only user's pipelines
     * @return List of additional pipelines
     */
    suspend fun loadMorePipelines(
        projectSlug: String,
        branch: String? = null,
        myPipelinesOnly: Boolean = false,
    ): List<Pipeline> {
        logger.info("Loading more pipelines for $projectSlug")

        try {
            // Get current next page token from state
            val currentData = stateStore.projectsData.value.data[projectSlug]
            val pageToken = currentData?.nextPageToken

            if (pageToken == null) {
                logger.debug("No more pages available for $projectSlug")
                return emptyList()
            }

            // Fetch next page
            val result =
                if (myPipelinesOnly) {
                    apiService.getPipelines(projectSlug, branch, pageToken)
                } else {
                    apiService.getPipelines(projectSlug, branch, pageToken)
                }

            return result.fold(
                onSuccess = { response ->
                    val pipelines = response.items.map { convertToPipeline(it) }

                    // Add to state store
                    stateStore.addPipelines(projectSlug, pipelines, response.nextPageToken)

                    logger.info("Loaded ${pipelines.size} more pipelines for $projectSlug")
                    pipelines
                },
                onFailure = { error ->
                    logger.error("Failed to load more pipelines: ${error.message}", error)
                    emptyList()
                },
            )
        } catch (e: Exception) {
            logger.error("Failed to load more pipelines for $projectSlug", e)
            return emptyList()
        }
    }

    /**
     * Invalidate cache for a project.
     */
    fun invalidateCache(projectSlug: String) {
        pipelineCache.invalidateMatching { it.startsWith(projectSlug) }
        logger.info("Invalidated pipeline cache for $projectSlug")
    }

    /**
     * Clear all cached data.
     */
    fun clearCache() {
        pipelineCache.clear()
        logger.info("Cleared all pipeline cache")
    }

    /**
     * Fetch pipelines from API.
     */
    private suspend fun fetchPipelinesFromApi(
        projectSlug: String,
        branch: String?,
        myPipelinesOnly: Boolean,
        pageDepth: Int = 1,
        fetchAll: Boolean = false,
    ): List<PipelineInfo> {
        val fetcher: suspend (String?) -> Result<com.circleci.idea.api.models.PaginatedResponse<PipelineInfo>> =
            { pageToken ->
                if (myPipelinesOnly) {
                    apiService.getPipelines(projectSlug, branch, pageToken)
                } else {
                    apiService.getPipelines(projectSlug, branch, pageToken)
                }
            }

        return if (fetchAll) {
            PaginationHelper.fetchAllPages(fetcher = fetcher)
        } else {
            val (items, _) = PaginationHelper.fetchMultiplePages(pageDepth, fetcher)
            items
        }
    }

    /**
     * Build cache key.
     */
    private fun buildCacheKey(
        projectSlug: String,
        branch: String?,
        myPipelinesOnly: Boolean,
    ): String {
        return "$projectSlug:${branch ?: "all"}:${if (myPipelinesOnly) "mine" else "all"}"
    }

    /**
     * Convert API PipelineInfo to domain Pipeline model.
     */
    private fun convertToPipeline(pipelineInfo: PipelineInfo): Pipeline {
        return Pipeline(
            id = pipelineInfo.id,
            number = pipelineInfo.number,
            projectSlug = pipelineInfo.projectSlug,
            state = pipelineInfo.state,
            createdAt = pipelineInfo.createdAt,
            branch = pipelineInfo.vcs?.branch,
            vcs =
                pipelineInfo.vcs?.let {
                    com.circleci.idea.state.VcsInfo(
                        branch = it.branch,
                        revision = it.revision,
                        commit =
                            it.commit?.let { commit ->
                                com.circleci.idea.state.CommitInfo(
                                    subject = commit.subject,
                                    body = commit.body,
                                )
                            },
                        providerName = it.providerName,
                    )
                },
            trigger =
                pipelineInfo.trigger?.let {
                    com.circleci.idea.state.TriggerInfo(
                        type = it.type,
                        actor =
                            it.actor?.let { actor ->
                                com.circleci.idea.state.Actor(
                                    login = actor.login,
                                    avatarUrl = actor.avatarUrl,
                                )
                            },
                    )
                },
        )
    }
}
