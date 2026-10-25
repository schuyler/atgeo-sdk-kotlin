package org.atgeo.client

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// fixtures/attribution-scenarios.json: SearchResult.attribution and SearchHit.collection (contract
// sections 4.10 and 8, runner rules in 13). The tile bodies need not validate against the lexicons, so the
// server here is raw, not the validating fake.

private const val GET_TILES = "$DEFAULT_SERVER/xrpc/org.atgeo.getTiles"

// What a tile with a status and no body is served as (contract §13).
private const val DEFAULT_ERROR_BODY = """{"error":"InternalServerError","message":"upstream error"}"""

private class Scenario(val json: JsonObject) {
    val name = json.getValue("name").stringOrNull()!!
    val dataset = json.getValue("dataset").stringOrNull()!!
    val collection = json["collection"].stringOrNull()
    val region = regionOf(json.getValue("region") as JsonObject)
    val tiles = (json.getValue("tiles") as JsonArray).map { it as JsonObject }
    val urls = tiles.map { it.getValue("url").stringOrNull()!! }
    val expectedAttribution = (json.getValue("expect") as JsonObject).getValue("attribution") as JsonArray
    val expectedHits = ((json.getValue("expect") as JsonObject).getValue("hits") as JsonArray).map { it as JsonObject }

    /** Answers every getTiles call with the scenario's URLs in listed order, and each tile URL exactly. */
    fun transport(delays: Map<String, Long> = emptyMap()) = HttpTransport { url ->
        delays[url]?.let { delay(it) }
        if (url == GET_TILES || url.startsWith("$GET_TILES?")) {
            return@HttpTransport HttpResponse(200, JsonObject(mapOf("tiles" to JsonArray(urls.map(::JsonPrimitive)))).toString().toByteArray())
        }
        val tile = tiles.firstOrNull { it.getValue("url").stringOrNull() == url } ?: return@HttpTransport HttpResponse(404, "Not found".toByteArray())
        val status = (tile["status"] as? JsonPrimitive)?.int ?: 200
        val body = tile["body"]?.toString() ?: DEFAULT_ERROR_BODY
        HttpResponse(status, body.toByteArray())
    }
}

private val scenarios = (fixture("attribution-scenarios.json") as JsonArray).map { Scenario(it as JsonObject) }

// An Attribution as the fixture spells it: a field the entry lacks is a missing key.
private fun Attribution.json() = JsonObject(
    listOfNotNull(
        "dataset" to JsonPrimitive(dataset),
        "collection" to JsonPrimitive(collection),
        release?.let { "release" to JsonPrimitive(it) },
        source?.let { "source" to JsonPrimitive(it) },
        licenses?.let { l -> "licenses" to JsonArray(l.map(::JsonPrimitive)) },
        attribution?.let { "attribution" to JsonPrimitive(it) },
    ).toMap(),
)


private suspend fun TestScope.searchOf(s: Scenario, transport: HttpTransport): SearchResult =
    client(transport).searchPlaces(s.region, s.dataset, collection = s.collection)

// A hit as one comparable string; a null collection is "null", never a missing key (a Kotlin property is always there).
private fun key(origin: String?, collection: Pair<String, String>?) =
    "${origin?.length}:$origin|${collection?.let { (d, c) -> "${d.length}:$d$c" }}"

/** The hits as a multiset of (origin, collection) pairs, order-insensitive. */
private fun gotHits(result: SearchResult) = result.places.map { key(it.origin, it.collection?.let { c -> c.dataset to c.collection }) }.sorted()

private fun Scenario.wantHits() = expectedHits.map { hit ->
    val c = hit["collection"] as? JsonObject
    key(hit.getValue("origin").stringOrNull(), c?.let { it.getValue("dataset").stringOrNull()!! to it.getValue("collection").stringOrNull()!! })
}.sorted()

class AttributionScenariosTest {
    @Test
    fun `has the scenarios the contract describes`() {
        assertEquals(12, scenarios.size)
    }

    @Nested
    @DisplayName("attribution scenarios")
    inner class Scenarios {
        // A fresh client per scenario: scenarios reuse tile URLs with different bodies, and the tile-list
        // cache is keyed by dataset, collection and bbox.
        @TestFactory
        fun scenarios(): List<DynamicTest> = scenarios.map { s ->
            dynamicTest(s.name) {
                runTest {
                    val result = searchOf(s, s.transport())
                    assertEquals(s.expectedAttribution, JsonArray(result.attribution.map { it.json() }))
                    assertEquals(s.wantHits(), gotHits(result))
                }
            }
        }

        @Test
        fun `serves a tile with a status and no body the default error body, as a failed tile of kind status`() = runTest {
            val failed = scenarios.first { it.name == "a failed tile contributes nothing" }
            val result = searchOf(failed, failed.transport())
            assertTrue(result.tileFailures.any { it.kind == TileFailure.Kind.STATUS && it.status == 500 })
            assertTrue(result.tileFailures.any { it.kind == TileFailure.Kind.DECODE })
            assertEquals(false, result.complete)
        }
    }

    // The result must not depend on which tile arrives first (contract §4.10 walks tiles in URL order, R7). The
    // transport delays each response in virtual time so that the highest URL arrives first.
    @Nested
    @DisplayName("attribution scenarios, tiles arriving in reverse URL order")
    inner class ReverseArrival {
        private val multi = scenarios.filter { it.tiles.size > 1 }

        @Test
        fun `has scenarios with more than one tile`() {
            assertTrue(multi.size >= 4, "${multi.size}")
        }

        @TestFactory
        fun `reverse arrival`(): List<DynamicTest> = multi.map { s ->
            dynamicTest(s.name) {
                runTest {
                    val sorted = s.urls.sorted()
                    val delays = sorted.withIndex().associate { (i, url) -> url to (sorted.size - 1 - i) * 25L }
                    val result = searchOf(s, s.transport(delays))
                    assertEquals(s.expectedAttribution, JsonArray(result.attribution.map { it.json() }))
                    assertEquals(s.wantHits(), gotHits(result))
                }
            }
        }
    }

    // The fixtures have no name outside the basic multilingual plane (contract §13, "Three things the fixtures do not pin").
    @Nested
    @DisplayName("beyond the fixtures")
    inner class BeyondTheFixtures {
        private fun tile(url: String, origin: String, name: String, collection: String) =
            """{"url": "https://places.atgeo.org/tiles/$url", "body": {"records": [{"value": {"${'$'}type": "org.atgeo.place", "origin": "$origin", "name": "$name",
                "locations": [{"${'$'}type": "community.lexicon.location.geo", "latitude": "37.770000", "longitude": "-122.415000"}]}}],
                "attribution": [{"dataset": "d", "collection": "$collection"}]}}"""

        private fun twoTiles(nameA: String, nameB: String) = Scenario(
            Json.parseToJsonElement(
                """{"name": "two tiles", "dataset": "d", "region": {"bbox": [-122.42, 37.76, -122.41, 37.78]},
                    "tiles": [${tile("a.json", "urn:atgeo:d:c1:1", nameA, "c1")}, ${tile("b.json", "urn:atgeo:d:c2:2", nameB, "c2")}],
                    "expect": {"attribution": [], "hits": []}}""",
            ) as JsonObject,
        )

        @Test
        fun `gives each hit the collection of its own tile, not of the first tile used`() = runTest {
            val s = twoTiles("Cafe", "Cafe")
            val hits = client(s.transport()).searchPlaces(s.region, "d").places
            assertEquals(
                setOf("urn:atgeo:d:c1:1" to "c1", "urn:atgeo:d:c2:2" to "c2"),
                hits.map { it.origin to it.collection?.collection }.toSet(),
            )
        }

        @Test
        fun `keeps each surviving hit with its own tile when ranking drops another record`() = runTest {
            val s = twoTiles("Zzzzqqq", "Cafe")
            val hits = client(s.transport()).searchPlaces(s.region, "d", text = "Cafe").places
            assertEquals(listOf("urn:atgeo:d:c2:2" to CollectionKey("d", "c2")), hits.map { it.origin to it.collection })
        }

        @Test
        fun `hands out attribution lists that refuse a cast and a change`() = runTest {
            val s = twoTiles("Cafe", "Cafe")
            val result = client(s.transport()).searchPlaces(s.region, "d")
            @Suppress("UNCHECKED_CAST")
            val list = result.attribution as MutableList<Any?>
            org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException::class.java) { list.add("changed") }
            org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException::class.java) { list.clear() }
        }

        @Test
        fun `sorts by UTF-16 code unit, so a supplementary character sorts before U+FFFD`() = runTest {
            val tile = """{"records": [], "attribution": [
                {"dataset": "\uFFFD", "collection": "c"}, {"dataset": "\uD83D\uDE00", "collection": "c"},
                {"dataset": "B", "collection": "c"}, {"dataset": "a", "collection": "c"}]}"""
            val scenario = Scenario(
                JsonObject(
                    mapOf(
                        "name" to JsonPrimitive("utf-16 order"), "dataset" to JsonPrimitive("d"), "region" to Json.parseToJsonElement("""{"bbox": [0, 0, 1, 1]}"""),
                        "tiles" to JsonArray(listOf(JsonObject(mapOf("url" to JsonPrimitive(tileUrl("a")), "body" to Json.parseToJsonElement(tile))))),
                        "expect" to JsonObject(mapOf("attribution" to JsonArray(emptyList()), "hits" to JsonArray(emptyList()))),
                    ),
                ),
            )
            val result = searchOf(scenario, scenario.transport())
            assertEquals(listOf("B", "a", "\uD83D\uDE00", "\uFFFD"), result.attribution.map { it.dataset })
        }
    }
}
