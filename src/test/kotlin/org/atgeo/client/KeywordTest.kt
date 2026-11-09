package org.atgeo.client

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DIVISION = "urn:atgeo:overturemaps.org:division:d1"

private val keywordTilesets = listOf(
    overture(
        listOf(
            FakeTile(
                "https://tiles.example/p.json",
                listOf(
                    record(place("starbucks-3", "Starbucks", listOf(point(37.7, -122.4))), 5),
                    record(place("starbucks-1", "Starbucks", listOf(point(37.775, -122.418)), mapOf("futureKey" to JsonPrimitive("x"))), 5),
                    record(place("starbucks-2", "Starbucks Reserve", listOf(point(37.8, -122.3))), 9),
                    record(place("mcd", "McDonald's", listOf(point(37.78, -122.41))), 7),
                ),
            ),
        ),
    ),
    FakeTileset(
        "overture-division", listOf(collectionOf("overturemaps.org", "division")),
        tiles = listOf(
            FakeTile("https://tiles.example/d.json", listOf(record(place("d1", "Starbucks District", listOf(point(37.9, -122.5)), mapOf("origin" to JsonPrimitive(DIVISION))), 1))),
        ),
    ),
)

@DisplayName("searchByKeyword")
class KeywordTest {
    private fun TestScope.setup(): Pair<FakeServer, AtgeoClient> = FakeServer(tilesets = keywordTilesets).let { it to client(it) }

    @Test
    fun `returns the server's matches in the server's order, as keyword hits`() = runTest {
        val (_, client) = setup()
        val result = client.searchByKeyword("starb", OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
        assertEquals(listOf("starbucks-2", "starbucks-1", "starbucks-3").map(::placeOrigin), result.places.map { it.origin })
        val hit = result.places[1]
        assertEquals(Via.KEYWORD, hit.via)
        assertEquals("Starbucks", hit.name)
        assertEquals(5L, hit.importance)
        assertEquals(CollectionKey("overturemaps.org", "place"), hit.collection)
        assertEquals(PlaceLocation.Point(37.775, -122.418), hit.location.point)
        assertNull(hit.location.bbox)
        assertEquals(Disclosure(true, null), result.disclosed)
    }

    @Test
    fun `credits each hit's own collection and the union of the places' attribution`() = runTest {
        val (_, client) = setup()
        val result = client.searchByKeyword("starb", OVERTURE_DATASET)
        assertEquals(listOf("place", "place", "place", "division"), result.places.map { it.collection?.collection })
        assertEquals(listOf("division", "place"), result.attribution.map { it.collection })
    }

    @Test
    fun `sends exactly dataset and q when given no collection or region`() = runTest {
        val (fake, client) = setup()
        client.searchByKeyword("Starb", OVERTURE_DATASET)
        assertEquals(1, fake.requests.size)
        assertEquals("$DEFAULT_SERVER/xrpc/org.atgeo.searchPlaces", endpointOf(fake.requests[0]))
        assertEquals(mapOf("dataset" to OVERTURE_DATASET, "q" to "Starb"), paramsOf(fake.requests[0]))
    }

    @Test
    fun `sends the collection and the snapped region when given`() = runTest {
        val (fake, client) = setup()
        val result = client.searchByKeyword("Starb", OVERTURE_DATASET, OVERTURE_COLLECTION, KeywordRegion(-122.43, 37.77, -122.41, 37.78))
        assertEquals(
            mapOf("dataset" to OVERTURE_DATASET, "collection" to OVERTURE_COLLECTION, "q" to "Starb", "bbox" to "-123,37,-122,38"),
            paramsOf(fake.requests.single()),
        )
        assertEquals(Disclosure(true, 1.0), result.disclosed)
    }

    @Test
    fun `sends the keyword exactly as passed, spaces and plus signs included`() = runTest {
        val (fake, client) = setup()
        client.searchByKeyword("  Zan +x&y=z  ", OVERTURE_DATASET)
        assertEquals("  Zan +x&y=z  ", paramsOf(fake.requests.single())["q"])
        assertTrue("%2B" in fake.requests.single())
    }

    @Test
    fun `sends a keyword the server cannot use and fails with the server's KeywordRequired`() = runTest {
        val (fake, client) = setup()
        val e = assertFailsWith<AtgeoException> { client.searchByKeyword("a", OVERTURE_DATASET) }
        assertEquals("KeywordRequired", e.code)
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun `is not cached, so a repeat is a second request`() = runTest {
        val (fake, client) = setup()
        client.searchByKeyword("Starb", OVERTURE_DATASET)
        client.searchByKeyword("Starb", OVERTURE_DATASET)
        assertEquals(2, fake.requests.size)
        // And getPlace on a keyword hit's origin still reaches the network: nothing entered the tile cache.
        client.getPlace(placeOrigin("mcd"))
        assertEquals(3, fake.requests.size)
    }

    @Nested
    @DisplayName("arguments")
    inner class Arguments {
        private suspend fun TestScope.refused(message: String, call: suspend (AtgeoClient) -> Unit) {
            val (fake, client) = setup()
            val e = assertFailsWith<IllegalArgumentException> { call(client) }
            assertTrue(message in e.message.orEmpty(), e.message)
            assertEquals(0, fake.requests.size)
        }

        @Test
        fun `refuses a bbox that fails the bbox validation, with no request`() = runTest {
            refused("invalid bbox") { it.searchByKeyword("ab", OVERTURE_DATASET, region = KeywordRegion(0.0, 10.0, 1.0, 5.0)) }
            refused("invalid bbox") { it.searchByKeyword("ab", OVERTURE_DATASET, region = KeywordRegion(Double.NaN, 1.0, 1.0, 5.0)) }
            refused("invalid bbox") { it.searchByKeyword("ab", OVERTURE_DATASET, region = KeywordRegion(0.0, 1.0, 181.0, 5.0)) }
        }

        @Test
        fun `accepts a box that crosses the antimeridian`() = runTest {
            val (fake, client) = setup()
            client.searchByKeyword("ab", OVERTURE_DATASET, region = KeywordRegion(170.0, 0.0, -170.0, 1.0))
            assertEquals("170,0,-170,1", paramsOf(fake.requests.single())["bbox"])
        }
    }
}
