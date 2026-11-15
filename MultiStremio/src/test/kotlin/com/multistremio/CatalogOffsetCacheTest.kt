package com.multistremio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogOffsetCacheTest {
    @Test
    fun cacheEvictsLeastRecentlyUsedCatalogsAtItsCapacity() {
        val cache = CatalogOffsetCache(capacity = 2, ttlNanos = 1_000L, nowNanos = { 0L })
        cache.put("a", 10)
        cache.put("b", 20)

        assertEquals(10, cache.get("a"))
        cache.put("c", 30)

        assertEquals(10, cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals(30, cache.get("c"))
        assertEquals(2, cache.sizeForTests())
    }

    @Test
    fun cacheExpiresOffsetsAfterTheConfiguredLifetime() {
        var now = 100L
        val cache = CatalogOffsetCache(
            capacity = 4,
            ttlNanos = 50L,
            nowNanos = { now },
        )
        cache.put("catalog", 25)
        assertEquals(25, cache.get("catalog"))

        now = 151L
        assertNull(cache.get("catalog"))
        assertEquals(0, cache.sizeForTests())
    }

    @Test
    fun cacheAcceptsOnlyPositiveCapacityAndKeepsTheMostRecentOffset() {
        val cache = CatalogOffsetCache(capacity = 0, ttlNanos = 1_000L, nowNanos = { 0L })
        cache.put("catalog", 7)
        assertEquals(7, cache.get("catalog"))
        assertEquals(1, cache.sizeForTests())
        assertTrue(cache.sizeForTests() <= 1)
    }
}
