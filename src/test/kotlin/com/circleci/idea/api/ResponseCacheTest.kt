package com.circleci.idea.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ResponseCacheTest {

    @Test
    fun `should store and retrieve values`() {
        val cache = ResponseCache<String, Int>()

        cache.put("key1", 100)
        cache.put("key2", 200)

        assertEquals(100, cache.get("key1"))
        assertEquals(200, cache.get("key2"))
    }

    @Test
    fun `should return null for missing keys`() {
        val cache = ResponseCache<String, Int>()

        assertNull(cache.get("missing"))
    }

    @Test
    fun `should expire entries after TTL`() = runBlocking {
        val cache = ResponseCache<String, Int>(defaultTtlMs = 100)

        cache.put("key", 100)
        assertEquals(100, cache.get("key"))

        delay(150)

        assertNull(cache.get("key"), "Entry should be expired")
    }

    @Test
    fun `should respect custom TTL`() = runBlocking {
        val cache = ResponseCache<String, Int>(defaultTtlMs = 1000)

        cache.put("key1", 100, ttlMs = 50)
        cache.put("key2", 200, ttlMs = 200)

        delay(100)

        assertNull(cache.get("key1"), "key1 should be expired")
        assertEquals(200, cache.get("key2"), "key2 should still be valid")
    }

    @Test
    fun `getOrPut should return cached value if present`() = runBlocking {
        val cache = ResponseCache<String, Int>()
        var computeCount = 0

        cache.put("key", 100)

        val value = cache.getOrPut("key") {
            computeCount++
            200
        }

        assertEquals(100, value)
        assertEquals(0, computeCount, "Compute function should not be called")
    }

    @Test
    fun `getOrPut should compute and cache if not present`() = runBlocking {
        val cache = ResponseCache<String, Int>()
        var computeCount = 0

        val value = cache.getOrPut("key") {
            computeCount++
            100
        }

        assertEquals(100, value)
        assertEquals(1, computeCount)
        assertEquals(100, cache.get("key"))
    }

    @Test
    fun `should invalidate specific key`() {
        val cache = ResponseCache<String, Int>()

        cache.put("key1", 100)
        cache.put("key2", 200)

        cache.invalidate("key1")

        assertNull(cache.get("key1"))
        assertEquals(200, cache.get("key2"))
    }

    @Test
    fun `should invalidate matching keys`() {
        val cache = ResponseCache<String, Int>()

        cache.put("user:1", 100)
        cache.put("user:2", 200)
        cache.put("post:1", 300)

        cache.invalidateMatching { it.startsWith("user:") }

        assertNull(cache.get("user:1"))
        assertNull(cache.get("user:2"))
        assertEquals(300, cache.get("post:1"))
    }

    @Test
    fun `should clear all entries`() {
        val cache = ResponseCache<String, Int>()

        cache.put("key1", 100)
        cache.put("key2", 200)
        cache.put("key3", 300)

        assertEquals(3, cache.size())

        cache.clear()

        assertEquals(0, cache.size())
        assertNull(cache.get("key1"))
        assertNull(cache.get("key2"))
        assertNull(cache.get("key3"))
    }

    @Test
    fun `cleanup should remove expired entries`() = runBlocking {
        val cache = ResponseCache<String, Int>(defaultTtlMs = 100)

        cache.put("key1", 100)
        cache.put("key2", 200)

        assertEquals(2, cache.size())

        delay(150)

        cache.cleanup()

        assertEquals(0, cache.size())
    }

    @Test
    fun `cleanup should preserve non-expired entries`() = runBlocking {
        val cache = ResponseCache<String, Int>(defaultTtlMs = 1000)

        cache.put("key1", 100, ttlMs = 50)
        cache.put("key2", 200, ttlMs = 500)

        delay(100)

        cache.cleanup()

        assertEquals(1, cache.size())
        assertEquals(200, cache.get("key2"))
    }
}
