package com.circleci.idea.api

import com.circleci.idea.logging.CircleCILogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple in-memory cache for API responses.
 *
 * @param defaultTtlMs Default time-to-live for cached items in milliseconds
 */
class ResponseCache<K, V>(
    private val defaultTtlMs: Long = 30_000, // 30 seconds
) {
    private val logger: CircleCILogger? =
        try {
            CircleCILogger.getInstance()
        } catch (e: Exception) {
            null
        }
    private val cache = ConcurrentHashMap<K, CacheEntry<V>>()

    private data class CacheEntry<V>(
        val value: V,
        val expiresAt: Long,
    )

    /**
     * Get a value from the cache.
     *
     * @param key Cache key
     * @return Cached value if present and not expired, null otherwise
     */
    fun get(key: K): V? {
        val entry = cache[key] ?: return null

        val now = System.currentTimeMillis()
        if (now > entry.expiresAt) {
            // Expired, remove from cache
            cache.remove(key)
            logger?.debug("Cache miss (expired): $key")
            return null
        }

        logger?.debug("Cache hit: $key")
        return entry.value
    }

    /**
     * Put a value in the cache.
     *
     * @param key Cache key
     * @param value Value to cache
     * @param ttlMs Time-to-live in milliseconds (defaults to defaultTtlMs)
     */
    fun put(
        key: K,
        value: V,
        ttlMs: Long = defaultTtlMs,
    ) {
        val expiresAt = System.currentTimeMillis() + ttlMs
        cache[key] = CacheEntry(value, expiresAt)
        logger?.debug("Cached: $key (TTL: ${ttlMs}ms)")
    }

    /**
     * Get or compute a value.
     * If the value is in cache and not expired, return it.
     * Otherwise, compute it, cache it, and return it.
     *
     * @param key Cache key
     * @param ttlMs Time-to-live in milliseconds
     * @param compute Function to compute the value if not cached
     * @return Cached or computed value
     */
    suspend fun getOrPut(
        key: K,
        ttlMs: Long = defaultTtlMs,
        compute: suspend () -> V,
    ): V {
        get(key)?.let { return it }

        val value = compute()
        put(key, value, ttlMs)
        return value
    }

    /**
     * Invalidate a cached value.
     *
     * @param key Cache key
     */
    fun invalidate(key: K) {
        cache.remove(key)
        logger?.debug("Invalidated cache: $key")
    }

    /**
     * Invalidate all cached values matching a predicate.
     *
     * @param predicate Predicate to match keys
     */
    fun invalidateMatching(predicate: (K) -> Boolean) {
        val keysToRemove = cache.keys.filter(predicate)
        keysToRemove.forEach { cache.remove(it) }
        logger?.debug("Invalidated ${keysToRemove.size} cache entries")
    }

    /**
     * Clear all cached values.
     */
    fun clear() {
        val size = cache.size
        cache.clear()
        logger?.debug("Cleared cache ($size entries)")
    }

    /**
     * Get cache size.
     */
    fun size(): Int = cache.size

    /**
     * Cleanup expired entries.
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        val expiredKeys = cache.filter { (_, entry) -> now > entry.expiresAt }.keys
        expiredKeys.forEach { cache.remove(it) }

        if (expiredKeys.isNotEmpty()) {
            logger?.debug("Cleaned up ${expiredKeys.size} expired cache entries")
        }
    }
}
