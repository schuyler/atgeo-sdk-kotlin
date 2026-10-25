package org.atgeo.client

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
                        assertFailsWith<AtgeoDecodingException> { client(server).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION) }
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
            assertFailsWith<AtgeoDecodingException> { client.searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION) }
            assertEquals(listOf(placeOrigin("a")), client.searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION).places.map { it.origin })
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
            val result = client(server).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertEquals(SearchResult.TileCounts(fetched = 1, failed = 0), result.tiles)
            assertEquals(listOf(GET_TILES, tileUrl("t")), server.requests)
        }
    }

    @Nested
    @DisplayName("tile records")
    inner class TileRecords {
        @Test
        fun `skip records that are not objects or have no object value, and keep the tile`() = runTest {
            val result = client(tileServing("""[7, "s", [1], null, {"value": null}, {}, {"importance": 3}, ${record(ok("a"))}]""")).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
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
            client.searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            server.goOffline()
            assertEquals(PlaceRecord(placeOrigin("a"), ok("a"), 4, emptyList()), client.getPlace(placeOrigin("a")))
        }

        @Test
        fun `read a non-string uri as absent`() = runTest {
            val noOrigin = place("x", "Anon", listOf(inside), mapOf("origin" to null))
            val result = client(tileServing("""[{"value": $noOrigin, "uri": {}}, {"value": $noOrigin, "uri": 9}]""")).searchPlaces(region, dataset = "overturemaps.org")
            assertEquals(listOf(null, null), result.places.map { it.origin })
        }

        @Test
        fun `read a non-number importance as absent`() = runTest {
            val result = client(tileServing("""[{"value": ${ok("a")}, "importance": "7"}, {"value": ${ok("b")}, "importance": [1]}]""")).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertEquals(listOf(null, null), result.places.map { it.importance })
        }
    }

    // robustness.test.ts "tile attribution" (contract §1, §4.4, §4.10).
    @Nested
    @DisplayName("tile attribution")
    inner class TileAttribution {
        private val good = """{"value": ${ok("good")}, "importance": 4}"""
        private val overturePlace = Attribution("overturemaps.org", "place")

        private suspend fun TestScope.search(tile: String): SearchResult {
            val server = FakeServer(
                responses = mapOf(GET_TILES to raw("""{"tiles": ["${tileUrl("a")}"]}"""), tileUrl("a") to raw(tile)),
            )
            return client(server).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
        }

        @Test
        fun `give a search whose tiles lack attribution an empty attribution and null hit collections`() = runTest {
            val result = search("""{"records": [$good]}""")
            assertEquals(emptyList(), result.attribution)
            assertEquals(1, result.places.size)
            for (hit in result.places) assertNull(hit.collection)
        }

        @TestFactory
        fun `do not fail the tile for an attribution that is not an array`(): List<DynamicTest> =
            listOf("a string" to "\"x\"", "an object" to "{}", "null" to "null", "a number" to "5").map { (name, attribution) ->
                dynamicTest(name) {
                    runTest {
                        val result = search("""{"records": [$good], "attribution": $attribution}""")
                        assertEquals(true, result.complete)
                        assertEquals(SearchResult.TileCounts(fetched = 1, failed = 0), result.tiles)
                        assertEquals(emptyList(), result.tileFailures)
                        assertEquals(emptyList(), result.attribution)
                        assertEquals(listOf(placeOrigin("good")), result.places.map { it.origin })
                    }
                }
            }

        @Test
        fun `drop the entries that lack a string dataset and collection, and keep the tile`() = runTest {
            val result = search(
                """{"records": [$good], "attribution": [null, 5, {"dataset": "d"}, {"dataset": 1, "collection": "c"}, {"dataset": "overturemaps.org", "collection": "place"}]}""",
            )
            assertEquals(true, result.complete)
            assertEquals(listOf(overturePlace), result.attribution)
            assertEquals(CollectionKey("overturemaps.org", "place"), result.places[0].collection)
        }
    }

    @Nested
    @DisplayName("place values")
    inner class PlaceValues {
        @Test
        fun `skip null and other non-object locations`() = runTest {
            val value = place("a", "A", listOf(JsonNull, JsonPrimitive(3), JsonArray(emptyList()), inside))
            assertEquals(listOf(placeOrigin("a")), client(tileServing("[${record(value)}]")).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION).places.map { it.origin })
        }

        @Test
        fun `skip null variants when the search has text`() = runTest {
            val value = place("m", "München", listOf(inside), mapOf("variants" to JsonArray(listOf(JsonNull, buildJsonObject { put("name", "Munich") }))))
            val hits = client(tileServing("[${record(value)}]")).searchPlaces(region, OVERTURE_DATASET, text = "Munich", collection = OVERTURE_COLLECTION).places
            assertEquals(listOf(1.0), hits.map { it.score })
        }

        @Test
        fun `read a coordinate given as an array as no location`() = runTest {
            val arrayed = JsonObject(inside + ("latitude" to JsonArray(listOf(JsonPrimitive(37.775)))))
            assertEquals(emptyList(), client(tileServing("[${record(place("a", "A", listOf(arrayed)))}]")).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION).places)
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
                        val error = assertFailsWith<AtgeoException> { client(server).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION) }
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
        fun `raise AtgeoDecodingException for a getPlace body with no object value`(): List<DynamicTest> =
            listOf(
                "null", "[]", "{}", """{"value": null}""", """{"value": []}""", """{"value": "x"}""", """{"uri": "x", "value": 5}""",
                // The earlier shape, record and no value (contract §1).
                """{"uri": "x", "record": {"name": "X"}}""",
            ).map { body ->
                dynamicTest(body) {
                    runTest {
                        val server = FakeServer(responses = mapOf(getPlace to raw(body)))
                        assertFailsWith<AtgeoDecodingException> { client(server).getPlace(placeOrigin("a")) }
                    }
                }
            }

        @Test
        fun `return the value as served, with an importance that is not a number as absent and no attribution as empty`() = runTest {
            val server = FakeServer(responses = mapOf(getPlace to raw("""{"uri": 5, "value": {"name": "X", "extra": {"n": [1]}}, "importance": "high"}""")))
            val answer = client(server).getPlace(placeOrigin("a"))
            assertEquals(PlaceRecord(placeOrigin("a"), Json.parseToJsonElement("""{"name": "X", "extra": {"n": [1]}}""") as JsonObject, null, emptyList()), answer)
        }

        @Test
        fun `succeed on the new-shape body and return its attribution as served`() = runTest {
            val body = """{"uri": "${placeOrigin("a")}", "url": "https://x.example/p", "importance": 3, "value": {"name": "X"}, "attribution": [{"dataset": "d", "collection": "c", "licenses": ["l"]}]}"""
            val answer = client(FakeServer(responses = mapOf(getPlace to raw(body)))).getPlace(placeOrigin("a"))
            assertEquals(
                PlaceRecord(placeOrigin("a"), buildJsonObject { put("name", "X") }, 3, listOf(Attribution("d", "c", licenses = listOf("l")))),
                answer,
            )
        }

        @TestFactory
        fun `read the attribution of a getPlace body as empty unless it is an array, and drop its unkeyed entries`(): List<DynamicTest> = listOf(
            "absent" to "" to emptyList<Attribution>(),
            "a string" to ", \"attribution\": \"x\"" to emptyList<Attribution>(),
            "null" to ", \"attribution\": null" to emptyList<Attribution>(),
            "an object" to ", \"attribution\": {}" to emptyList<Attribution>(),
            "mixed" to (
                ", \"attribution\": [5, null, {\"dataset\": \"d\"}, {\"dataset\": \"d\", \"collection\": \"c\"}, " +
                    "{\"dataset\": \"d\", \"collection\": \"c\", \"release\": \"2\"}]"
                ) to listOf(Attribution("d", "c"), Attribution("d", "c", release = "2")),
        ).map { (named, expected) ->
            dynamicTest(named.first) {
                runTest {
                    val body = """{"uri": "x", "value": {"name": "X"}${named.second}}"""
                    val answer = client(FakeServer(responses = mapOf(getPlace to raw(body)))).getPlace(placeOrigin("a"))
                    assertEquals(expected, answer.attribution)
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
