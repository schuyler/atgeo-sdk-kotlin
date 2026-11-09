package org.atgeo.client

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// What each result reports the server learned during that call (contract §4.11, §12 item 9).
@DisplayName("disclosed")
class DisclosedTest {
    private val tiles = listOf(FakeTile(tileUrl("p"), listOf(record(place("zc", "Zanzibar Cafe", listOf(point(37.772, -122.412))), 5))))
    private val region = Region.Bbox(-122.42, 37.77, -122.41, 37.78)

    private fun TestScope.setup(tilesFor: ((String) -> TilesAnswer)? = null): Pair<FakeServer, AtgeoClient> =
        FakeServer(tilesets = listOf(overture(tiles)), tilesFor = tilesFor).let { it to client(it) }

    @Test
    fun `is the 0_01 degree grid, no keyword, on a cold nearby search, whose hits come from tiles`() = runTest {
        val result = setup().second.searchNearby(region, OVERTURE_DATASET, "Zanzibar", OVERTURE_COLLECTION)
        assertEquals(Disclosure(false, 0.01), result.disclosed)
        assertTrue(result.places.isNotEmpty())
        assertTrue(result.places.all { it.via == Via.TILES })
    }

    @Test
    fun `reports no region for a nearby search answered from cache`() = runTest {
        val client = setup().second
        client.searchNearby(region, OVERTURE_DATASET)
        assertEquals(NOTHING_SENT, client.searchNearby(region, OVERTURE_DATASET, "Zanzibar").disclosed)
    }

    @Test
    fun `reports the keyword and a 1 degree region for a keyword search with a region, none without`() = runTest {
        val client = setup().second
        assertEquals(Disclosure(true, 1.0), client.searchByKeyword("Zanzibar", OVERTURE_DATASET, region = KeywordRegion(-122.42, 37.77, -122.41, 37.78)).disclosed)
        assertEquals(Disclosure(true, null), client.searchByKeyword("Zanzibar", OVERTURE_DATASET).disclosed)
    }

    @Test
    fun `counts a getTiles request as sent even when it lists no tiles`() = runTest {
        val (fake, client) = setup { TilesAnswer.Urls(emptyList()) }
        val result = client.searchNearby(region, OVERTURE_DATASET)
        assertEquals(1, fake.requests.size)
        assertEquals(Disclosure(false, 0.01), result.disclosed)
    }

    @Test
    fun `does not count a tile the client refused to fetch`() = runTest {
        val (fake, client) = setup { TilesAnswer.Urls(listOf("ftp://tiles.example/p.json")) }
        client.searchNearby(region, OVERTURE_DATASET)
        val before = fake.requests.size
        val warm = client.searchNearby(region, OVERTURE_DATASET)
        assertEquals(before, fake.requests.size)
        assertEquals(listOf(TileFailure.Kind.REFUSED), warm.tileFailures.map { it.kind })
        assertEquals(NOTHING_SENT, warm.disclosed)
    }

    @Test
    fun `counts a tile fetch whose tile list is cached but whose tile was evicted`() = runTest {
        val fake = FakeServer(tilesets = listOf(overture(tiles)))
        val client = AtgeoClient(DEFAULT_SERVER, fake, 1, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        client.searchNearby(region, OVERTURE_DATASET)
        assertEquals(Disclosure(false, 0.01), client.searchNearby(region, OVERTURE_DATASET).disclosed)
    }
}
