package org.atgeo.client

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private val region = Region.Bbox(-122.42, 37.77, -122.41, 37.78)
private val inside = point(37.775, -122.415)
private val GET_TILES = Xrpc(DEFAULT_SERVER, { error("unused") })
    .url("org.atgeo.getTiles", listOf("dataset" to "overturemaps.org", "collection" to "place", "bbox" to "-122.42,37.77,-122.41,37.78"))

// The getTiles URL of a whole-dataset search, which sends no collection.
private val GET_TILES_DATASET = Xrpc(DEFAULT_SERVER, { error("unused") })
    .url("org.atgeo.getTiles", listOf("dataset" to "overturemaps.org", "bbox" to "-122.42,37.77,-122.41,37.78"))

private fun raw(body: String, status: Int = 200) = HttpResponse(status, body.toByteArray())

private fun ok(id: String, name: String = id) = place(id, name, listOf(inside))

/** A server whose tile list is one tile at tileUrl("t") holding `records` (served raw, unvalidated). */
private fun tileServing(records: String) = FakeServer(
    responses = mapOf(
        GET_TILES to raw("""{"tiles": ["${tileUrl("t")}"]}"""),
        GET_TILES_DATASET to raw("""{"tiles": ["${tileUrl("t")}"]}"""),
        tileUrl("t") to raw("""{"records": $records}"""),
    ),
)

/**
 * Contract §16: reference behaviours a port must not reproduce (crashes, wrong counts, laxer typing).
 * The reference crashes on these, so the JS suite cannot test them.
 */
class RobustnessTest {
    @Nested
    @DisplayName("getTiles bodies")
    inner class GetTilesBodies {
        @TestFactory
        fun `raise AtgeoDecodingException without a tiles array`(): List<DynamicTest> =
            listOf("{}", "[]", """{"tiles": "x"}""", "null").map { body ->
                dynamicTest(body) {
                    runTest {
                        val server = FakeServer(responses = mapOf(GET_TILES to raw(body)))
                        assertFailsWith<AtgeoDecodingException> { client(server).searchPlaces(region) }
                    }
                }
            }

        @Test
        fun `cache nothing when the tile list is unusable`() = runTest {
            var calls = 0
            val good = FakeServer(tilesets = listOf(overture(listOf(FakeTile(tileUrl("a"), listOf(record(ok("a"))))))))
            val flaky = HttpTransport { url ->
                if (url == GET_TILES && calls++ == 0) raw("{}") else good.get(url)
            }
            val client = client(flaky)
            assertFailsWith<AtgeoDecodingException> { client.searchPlaces(region) }
            assertEquals(listOf(placeOrigin("a")), client.searchPlaces(region).places.map { it.origin })
            assertEquals(2, calls)
        }

        @Test
        fun `skip non-string tile entries instead of fetching them`() = runTest {
            val server = FakeServer(
                responses = mapOf(
                    GET_TILES to raw("""{"tiles": [1, null, {}, "${tileUrl("t")}", ["x"]]}"""),
                    tileUrl("t") to raw("""{"records": [${record(ok("a"))}]}"""),
                ),
            )
            val result = client(server).searchPlaces(region)
            assertEquals(SearchResult.TileCounts(fetched = 1, failed = 0), result.tiles)
            assertEquals(listOf(GET_TILES, tileUrl("t")), server.requests)
        }
    }

    @Nested
    @DisplayName("tile records")
    inner class TileRecords {
        @Test
        fun `skip records that are not objects or have no object value, and keep the tile`() = runTest {
            val result = client(tileServing("""[7, "s", [1], null, {"value": null}, {}, {"importance": 3}, ${record(ok("a"))}]""")).searchPlaces(region)
            assertEquals(SearchResult.TileCounts(fetched = 1, failed = 0), result.tiles)
            assertEquals(listOf(placeOrigin("a")), result.places.map { it.origin })
        }

        @Test
        fun `skip a scalar value without consuming its dedupe key`() = runTest {
            val noOrigin = place("x", "By uri", listOf(inside), mapOf("origin" to null))
            val result = client(tileServing("""[{"value": 5, "uri": "u"}, ${record(noOrigin, uri = "u")}]""")).searchPlaces(region, dataset = "overturemaps.org")
            assertEquals(listOf("u"), result.places.map { it.origin })
        }

        @Test
        fun `keep answering getPlace from a tile that held bad records`() = runTest {
            val server = tileServing("""[null, 7, ${record(ok("a"), 4)}]""")
            val client = client(server)
            client.searchPlaces(region)
            server.goOffline()
            assertEquals(PlaceRecord(placeOrigin("a"), ok("a"), 4), client.getPlace(placeOrigin("a")))
        }

        @Test
        fun `read a non-string uri as absent`() = runTest {
            val noOrigin = place("x", "Anon", listOf(inside), mapOf("origin" to null))
            val result = client(tileServing("""[{"value": $noOrigin, "uri": {}}, {"value": $noOrigin, "uri": 9}]""")).searchPlaces(region, dataset = "overturemaps.org")
            assertEquals(listOf(null, null), result.places.map { it.origin })
        }

        @Test
        fun `read a non-number importance as absent`() = runTest {
            val result = client(tileServing("""[{"value": ${ok("a")}, "importance": "7"}, {"value": ${ok("b")}, "importance": [1]}]""")).searchPlaces(region)
            assertEquals(listOf(null, null), result.places.map { it.importance })
        }
    }

    @Nested
    @DisplayName("place values")
    inner class PlaceValues {
        @Test
        fun `skip null and other non-object locations`() = runTest {
            val value = place("a", "A", listOf(JsonNull, JsonPrimitive(3), JsonArray(emptyList()), inside))
            assertEquals(listOf(placeOrigin("a")), client(tileServing("[${record(value)}]")).searchPlaces(region).places.map { it.origin })
        }

        @Test
        fun `skip null variants when the search has text`() = runTest {
            val value = place("m", "München", listOf(inside), mapOf("variants" to JsonArray(listOf(JsonNull, buildJsonObject { put("name", "Munich") }))))
            val hits = client(tileServing("[${record(value)}]")).searchPlaces(region, text = "Munich").places
            assertEquals(listOf(1.0), hits.map { it.score })
        }

        @Test
        fun `read a coordinate given as an array as no location`() = runTest {
            val arrayed = JsonObject(inside + ("latitude" to JsonArray(listOf(JsonPrimitive(37.775)))))
            assertEquals(emptyList(), client(tileServing("[${record(place("a", "A", listOf(arrayed)))}]")).searchPlaces(region).places)
        }
    }

    @Nested
    @DisplayName("error bodies")
    inner class ErrorBodies {
        @TestFactory
        fun `read non-string error and message fields as absent`(): List<DynamicTest> =
            listOf("""{"error": 7, "message": {}}""", "null", """{"error": null}""", "[1]").map { body ->
                dynamicTest(body) {
                    runTest {
                        val server = FakeServer(responses = mapOf(GET_TILES to raw(body, 400)))
                        val error = assertFailsWith<AtgeoException> { client(server).searchPlaces(region) }
                        assertEquals("HttpError" to "HTTP 400", error.code to error.message)
                    }
                }
            }
    }

    @Nested
    @DisplayName("getPlace and describe bodies")
    inner class OtherBodies {
        private val getPlace = Xrpc(DEFAULT_SERVER, { error("unused") }).url("org.atgeo.getPlace", listOf("uri" to placeOrigin("a")))
        private val describeService = "$DEFAULT_SERVER/xrpc/org.atgeo.describeService"
        private val describeTileset = Xrpc(DEFAULT_SERVER, { error("unused") }).url("org.atgeo.describeTileset", listOf("slug" to "s"))

        @TestFactory
        fun `raise AtgeoDecodingException for a getPlace body with no object record`(): List<DynamicTest> =
            listOf("{}", "null", """{"uri": "x", "record": 5}""", """{"record": null}""").map { body ->
                dynamicTest(body) {
                    runTest {
                        val server = FakeServer(responses = mapOf(getPlace to raw(body)))
                        assertFailsWith<AtgeoDecodingException> { client(server).getPlace(placeOrigin("a")) }
                    }
                }
            }

        @TestFactory
        fun `raise AtgeoDecodingException for a describe body that is not an object`(): List<DynamicTest> =
            listOf("null", "false", "0", "\"\"", "[]").flatMap { body ->
                listOf(
                    dynamicTest("describeService $body") {
                        runTest {
                            val server = FakeServer(responses = mapOf(describeService to raw(body)))
                            assertFailsWith<AtgeoDecodingException> { client(server).describeService() }
                        }
                    },
                    dynamicTest("describeTileset $body") {
                        runTest {
                            val server = FakeServer(responses = mapOf(describeTileset to raw(body)))
                            assertFailsWith<AtgeoDecodingException> { client(server).describeTileset("s") }
                        }
                    },
                )
            }

        @Test
        fun `map a describe body of wrong-typed fields without failing`() = runTest {
            val server = FakeServer(responses = mapOf(describeService to raw("""{"did": 5, "tilesets": [{"slug": [], "collections": 3}, 7]}""")))
            val service = client(server).describeService()
            assertNull(service.did)
            assertEquals(listOf(ServiceDescription.ServedTileset(null, emptyList())), service.tilesets)
        }
    }
}
