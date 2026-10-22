package org.atgeo.client

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun entry(size: Long, vararg origins: String) = TileCache.Entry(
    origins.map { TileRecord(JsonObject(mapOf("origin" to JsonPrimitive(it), "name" to JsonPrimitive("$it@$size")))) },
    size,
)

private val region = Region.Bbox(-122.42, 37.77, -122.41, 37.78)
private const val OTHER_ORIGIN = "urn:atgeo:example.org:thing:shared"

private fun setup() = FakeServer(
    tilesets = listOf(
        overture(
            listOf(
                FakeTile(
                    tileUrl("a"),
                    listOf(
                        record(place("a", "Alpha", listOf(point(37.772, -122.412))), 3),
                        record(place("shared", "Shared in A", listOf(point(37.773, -122.413))), 2),
                    ),
                ),
            ),
        ),
        FakeTileset(
            slug = "example-thing",
            collections = listOf(collectionOf("example.org", "thing")),
            tiles = listOf(
                FakeTile(
                    tileUrl("o"),
                    listOf(record(place("shared", "Shared in other", listOf(point(10.0, 10.0)), mapOf("origin" to JsonPrimitive(OTHER_ORIGIN))))),
                ),
            ),
        ),
    ),
)

private fun tileListCount(requests: List<String>) = requests.count { "org.atgeo.getTiles" in it }

// The reference fakes Date; the TTL caches here take the internal constructor's clock (spec §5).
private const val MIDNIGHT = 1_767_225_600_000L // 2026-01-01T00:00:00Z

class CacheTest {
    @Nested
    @DisplayName("warm regions")
    inner class WarmRegions {
        @Test
        fun `answers an identical second search offline`() = runTest {
            val fake = setup()
            val client = client(fake)
            val first = client.searchPlaces(region, text = "Alpha")
            fake.goOffline()
            val second = client.searchPlaces(region, text = "Alpha")
            assertEquals(first, second)
            assertTrue(first.places.isNotEmpty())
        }

        @Test
        fun `reuses tiles for a different text over the same region`() = runTest {
            val fake = setup()
            val client = client(fake)
            client.searchPlaces(region)
            val before = fake.requests.size
            client.searchPlaces(region, text = "Alpha")
            assertEquals(before, fake.requests.size)
        }

        @Test
        fun `makes no tile request for a repeated search`() = runTest {
            val fake = setup()
            val client = client(fake)
            client.searchPlaces(region)
            client.searchPlaces(region)
            assertEquals(1, fake.requests.count { it == tileUrl("a") })
        }
    }

    @Nested
    @DisplayName("tile-list expiry")
    inner class TileListExpiry {
        @Test
        fun `expires after 3600 s and then requires network`() = runTest {
            var now = MIDNIGHT
            val fake = setup()
            val client = client(fake, clock = { now })
            client.searchPlaces(region)
            val base = fake.requests.size

            now = MIDNIGHT + 3_599_000 // 00:59:59
            client.searchPlaces(region)
            assertEquals(base, fake.requests.size)

            now = MIDNIGHT + 3_601_000 // 01:00:01
            client.searchPlaces(region)
            assertEquals(2, tileListCount(fake.requests))
            assertEquals(1, fake.requests.count { it == tileUrl("a") })
        }

        @Test
        fun `fails offline once the tile list has expired`() = runTest {
            var now = MIDNIGHT
            val fake = setup()
            val client = client(fake, clock = { now })
            client.searchPlaces(region)
            fake.goOffline()
            now = MIDNIGHT + 3_601_000
            assertFails { client.searchPlaces(region) }
        }

        @Test
        fun `caches describeService for 3600 s`() = runTest {
            var now = MIDNIGHT
            val fake = setup()
            val client = client(fake, clock = { now })
            client.describeService()
            now = MIDNIGHT + 3_599_000
            client.describeService()
            assertEquals(1, fake.requests.size)
            now = MIDNIGHT + 3_601_000
            client.describeService()
            assertEquals(2, fake.requests.size)
        }
    }

    @Nested
    @DisplayName("getPlace cache scan")
    inner class GetPlaceCacheScan {
        @Test
        fun `answers from an already-fetched tile without a request`() = runTest {
            val fake = setup()
            val client = client(fake)
            client.searchPlaces(region)
            val before = fake.requests.size
            val found = client.getPlace(placeOrigin("a"))
            assertEquals(before, fake.requests.size)
            assertEquals(PlaceRecord(placeOrigin("a"), place("a", "Alpha", listOf(point(37.772, -122.412))), 3), found)
        }

        @Test
        fun `answers offline for a cached origin`() = runTest {
            val fake = setup()
            val client = client(fake)
            client.searchPlaces(region)
            fake.goOffline()
            assertEquals("Shared in A", client.getPlace(placeOrigin("shared")).place["name"].stringOrNull())
        }

        @Test
        fun `matches the whole origin, not a shared id`() = runTest {
            val fake = setup()
            val client = client(fake)
            client.searchPlaces(region)
            val before = fake.requests.size
            val found = client.getPlace(OTHER_ORIGIN)
            val getPlaces = fake.requests.drop(before).filter { endpointOf(it).endsWith("/xrpc/org.atgeo.getPlace") }
            assertEquals(1, getPlaces.size)
            assertEquals(OTHER_ORIGIN, paramsOf(getPlaces[0])["uri"])
            assertEquals("Shared in other", found.place["name"].stringOrNull())
        }

        @Test
        fun `returns the copy from the lowest tile URL, although a higher one was cached first`() = runTest {
            fun copy(name: String) = record(place("x", name, listOf(point(37.775, -122.415))))
            val fake = FakeServer(
                tilesets = listOf(overture(listOf(FakeTile(tileUrl("a"), listOf(copy("Copy A"))), FakeTile(tileUrl("b"), listOf(copy("Copy B")))))),
                tilesFor = { bbox -> TilesAnswer.Urls(listOf(if (bbox.startsWith("-122")) tileUrl("a") else tileUrl("b"))) },
            )
            val client = client(fake)
            client.searchPlaces(Region.Bbox(-100.5, 10.5, -100.4, 10.6))
            assertEquals("Copy B", client.getPlace(placeOrigin("x")).place["name"].stringOrNull())
            client.searchPlaces(region)
            fake.goOffline()
            assertEquals("Copy A", client.getPlace(placeOrigin("x")).place["name"].stringOrNull())
        }
    }

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
