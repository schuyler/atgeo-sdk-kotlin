package org.atgeo.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

private fun entry(size: Long, vararg origins: String) = TileCache.Entry(
    origins.map { TileRecord(JsonObject(mapOf("origin" to JsonPrimitive(it), "name" to JsonPrimitive("$it@$size")))) },
    size,
)

class CacheTest {
    // The reference suite reaches the caches only through the client; these pin contract §10 directly.
    @Nested
    @DisplayName("TileCache (no reference counterpart)")
    inner class TileCacheUnit {
        @Test
        fun `evicts least recently used tiles until within budget`() {
            val cache = TileCache(100)
            cache.set("a", entry(40))
            cache.set("b", entry(40))
            cache.get("a")
            cache.set("c", entry(40))
            assertEquals(listOf("a", "c"), cache.urls())
            assertEquals(80, cache.totalSize)
        }

        @Test
        fun `evicts several tiles for one large insert`() {
            val cache = TileCache(100)
            cache.set("a", entry(30))
            cache.set("b", entry(30))
            cache.set("c", entry(30))
            cache.set("d", entry(90))
            assertEquals(listOf("d"), cache.urls())
            assertEquals(90, cache.totalSize)
        }

        @Test
        fun `a tile larger than the budget evicts everything, itself included`() {
            val cache = TileCache(100)
            cache.set("a", entry(30))
            cache.set("big", entry(101))
            assertEquals(emptyList(), cache.urls())
            assertEquals(0, cache.totalSize)
        }

        @Test
        fun `replacing a URL subtracts the old size and makes it most recent`() {
            val cache = TileCache(100)
            cache.set("a", entry(60))
            cache.set("b", entry(10))
            cache.set("a", entry(50))
            assertEquals(listOf("b", "a"), cache.urls())
            assertEquals(60, cache.totalSize)
        }

        @Test
        fun `find takes the record from the lowest URL and leaves recency alone`() {
            val cache = TileCache(1000)
            cache.set("https://t/b", entry(10, "x"))
            cache.set("https://t/a", entry(20, "x", "y"))
            cache.set("https://t/c", entry(30, "x"))
            val before = cache.urls()
            assertEquals("x@20", cache.find("x")?.value?.get("name")?.stringOrNull())
            assertEquals("y@20", cache.find("y")?.value?.get("name")?.stringOrNull())
            assertNull(cache.find("z"))
            assertEquals(before, cache.urls())
        }

        @Test
        fun `find matches by record key, falling back to uri`() {
            val cache = TileCache(1000)
            val byUri = TileRecord(JsonObject(emptyMap()), uri = "https://example.org/r/1")
            cache.set("t", TileCache.Entry(listOf(byUri), 1))
            assertSame(byUri, cache.find("https://example.org/r/1"))
        }
    }

    @Nested
    @DisplayName("TtlCache (no reference counterpart)")
    inner class TtlCacheUnit {
        @Test
        fun `is fresh until exactly 3,600,000 ms after being set`() {
            var now = 1_000_000L
            val cache = TtlCache<String>(3_600_000) { now }
            cache.set("k", "v")
            now += 3_599_999
            assertEquals("v", cache.get("k"))
            now += 1
            assertNull(cache.get("k"))
        }

        @Test
        fun `setting again restarts the clock`() {
            var now = 0L
            val cache = TtlCache<String>(3_600_000) { now }
            cache.set("k", "v1")
            now = 3_000_000
            cache.set("k", "v2")
            now = 6_000_000
            assertEquals("v2", cache.get("k"))
            assertNull(cache.get("other"))
        }
    }
}
