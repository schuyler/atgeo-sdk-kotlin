package org.atgeo.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val SERVER = "https://places.atgeo.org"

/** Answers getTiles with `tiles`, each tile with its body, searchPlaces with `answer`, and records every request. */
private class RawServer(
    private val tiles: List<Pair<String, JsonElement>>,
    private val answer: JsonElement?,
    private val onSearch: (suspend () -> HttpResponse)? = null,
) : HttpTransport {
    val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
    val searches get() = requests.filter { it.startsWith("$SERVER/xrpc/org.atgeo.searchPlaces") }

    override suspend fun get(url: String): HttpResponse {
        requests += url
        return when {
            url.startsWith("$SERVER/xrpc/org.atgeo.getTiles") -> HttpResponse(200, buildJsonObject { put("tiles", JsonArray(tiles.map { JsonPrimitive(it.first) })) }.toString().toByteArray())
            url.startsWith("$SERVER/xrpc/org.atgeo.searchPlaces") -> onSearch?.invoke() ?: HttpResponse(200, answer.toString().toByteArray())
            else -> tiles.firstOrNull { it.first == url }?.let { HttpResponse(200, it.second.toString().toByteArray()) } ?: HttpResponse(404, "Not found".toByteArray())
        }
    }
}

@DisplayName("searchNearbyThenByKeyword")
class KeywordFallbackTest {
    private fun regionOf(o: JsonObject): Region = (o["bbox"] as? JsonArray)?.map { it.jsonPrimitive.double }
        ?.let { Region.Bbox(it[0], it[1], it[2], it[3]) }
        ?: Region.Circle(o.getValue("lat").jsonPrimitive.double, o.getValue("lon").jsonPrimitive.double, o.getValue("radius").jsonPrimitive.double)

    private fun sortOf(s: String?) = when (s) { null, "fused" -> SortMode.FUSED; "distance" -> SortMode.DISTANCE; else -> SortMode.SIMILARITY }

    @TestFactory
    fun `keyword-fallback scenarios`(): List<DynamicTest> = (fixture("keyword-fallback.json") as JsonArray).map { element ->
        val s = element as JsonObject
        dynamicTest(s.getValue("name").jsonPrimitive.content) {
            runTest {
                val tiles = (s.getValue("tiles") as JsonArray).map { (it as JsonObject).getValue("url").jsonPrimitive.content to it.getValue("body") }
                val raw = RawServer(tiles, s.getValue("keywordAnswer"))
                val expected = s.getValue("expect") as JsonObject
                val keyword = s.getValue("keyword").jsonPrimitive.content
                val collection = s["collection"]?.jsonPrimitive?.content
                val result = client(raw).searchNearbyThenByKeyword(
                    regionOf(s.getValue("region") as JsonObject), s.getValue("dataset").jsonPrimitive.content, keyword,
                    if (s.getValue("keywordRegion").jsonPrimitive.content == "degree") KeywordRegionMode.DEGREE else KeywordRegionMode.NONE,
                    collection, sortOf(s["sort"]?.jsonPrimitive?.content),
                )
                assertEquals((expected.getValue("origins") as JsonArray).map { it.jsonPrimitive.content }, result.places.map { it.origin })
                val d = expected.getValue("disclosed") as JsonObject
                assertEquals(Disclosure(d.getValue("keyword").jsonPrimitive.boolean, (d["region"] as? JsonPrimitive)?.takeIf { it.content != "null" }?.double), result.disclosed)
                val requested = expected.getValue("keywordRequested").jsonPrimitive.boolean
                assertEquals(if (requested) 1 else 0, raw.searches.size)
                if (requested) {
                    val want = buildMap {
                        put("dataset", s.getValue("dataset").jsonPrimitive.content)
                        if (collection != null) put("collection", collection)
                        put("q", keyword)
                        expected["bbox"]?.let { put("bbox", it.jsonPrimitive.content) }
                    }
                    assertEquals(want, paramsOf(raw.searches[0]))
                }
            }
        }
    }

    private val region = Region.Bbox(-122.42, 37.77, -122.41, 37.78)
    private val tiles = listOf(FakeTile("https://tiles.example/p.json", listOf(record(place("zc", "Zanzibar Cafe", listOf(point(37.772, -122.412))), 5))))

    @Nested
    @DisplayName("arguments")
    inner class Arguments {
        private suspend fun TestScope.refused(message: String, call: suspend (AtgeoClient) -> Unit) {
            val raw = RawServer(emptyList(), null)
            val e = assertFailsWith<IllegalArgumentException> { call(client(raw)) }
            assertTrue(message in e.message.orEmpty(), e.message)
            assertEquals(0, raw.requests.size)
        }

        @Test
        fun `refuses an invalid region before the sort, and the sort for a keyword that normalizes to nothing`() = runTest {
            refused("invalid bbox") { it.searchNearbyThenByKeyword(Region.Bbox(0.0, 10.0, 1.0, 5.0), "d", "  ", KeywordRegionMode.NONE, sort = SortMode.SIMILARITY) }
            refused("SIMILARITY") { it.searchNearbyThenByKeyword(region, "d", "  ", KeywordRegionMode.NONE, sort = SortMode.SIMILARITY) }
        }
    }

    @Nested
    @DisplayName("when the server refuses a keyword the SDK judged qualifying")
    inner class Refused {
        @Test
        fun `returns the nearby result, disclosing the keyword it sent`() = runTest {
            val fake = FakeServer(tilesets = listOf(overture(tiles)))
            val client = client(fake)
            val nearby = client(FakeServer(tilesets = listOf(overture(tiles)))).searchNearby(region, OVERTURE_DATASET, "ﬁ", OVERTURE_COLLECTION)
            val result = client.searchNearbyThenByKeyword(region, OVERTURE_DATASET, "ﬁ", KeywordRegionMode.DEGREE, OVERTURE_COLLECTION)
            assertEquals(1, fake.requests.count { "org.atgeo.searchPlaces" in it })
            assertEquals(nearby.places, result.places)
            assertEquals(Disclosure(true, 0.01), result.disclosed)
        }

        @Test
        fun `reports the one-degree region of a refused keyword when nothing else was sent`() = runTest {
            val fake = FakeServer(tilesets = listOf(overture(tiles)))
            val client = client(fake)
            client.searchNearby(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertEquals(Disclosure(true, 1.0), client.searchNearbyThenByKeyword(region, OVERTURE_DATASET, "ﬁ", KeywordRegionMode.DEGREE, OVERTURE_COLLECTION).disclosed)
            assertEquals(Disclosure(true, null), client.searchNearbyThenByKeyword(region, OVERTURE_DATASET, "ﬁ", KeywordRegionMode.NONE, OVERTURE_COLLECTION).disclosed)
        }

        @Test
        fun `still fails with any other refusal of the keyword request`() = runTest {
            val raw = RawServer(emptyList(), null) { HttpResponse(400, """{"error":"InvalidRequest","message":"q is too long"}""".toByteArray()) }
            val e = assertFailsWith<AtgeoException> { client(raw).searchNearbyThenByKeyword(region, "d", "Quokka", KeywordRegionMode.NONE) }
            assertEquals("InvalidRequest", e.code)
        }
    }

    // Raw server, so the bodies and attribution can be chosen freely.
    @Nested
    @DisplayName("pins")
    inner class Pins {
        private val tileUrl = "$SERVER/tiles/t.json"
        private val loc = buildJsonArray { add(buildJsonObject { put("\$type", "community.lexicon.location.geo"); put("latitude", "37.775000"); put("longitude", "-122.415000") }) }
        private fun entry(text: String) = buildJsonObject { put("dataset", "overturemaps.org"); put("collection", "place"); put("attribution", text) }

        @Test
        fun `never leaves out a nearby hit with no origin, even when a keyword hit has none either`() = runTest {
            val tile = buildJsonObject {
                put("records", buildJsonArray { add(buildJsonObject { put("value", buildJsonObject { put("name", "Quokka Lounge"); put("locations", loc) }) }) })
                put("attribution", JsonArray(emptyList()))
            }
            val answer = buildJsonObject {
                put("places", buildJsonArray { add(buildJsonObject { put("value", buildJsonObject { put("name", "Qwokka Bar"); put("locations", loc) }); put("attribution", JsonArray(emptyList())) }) })
            }
            val result = client(RawServer(listOf(tileUrl to tile), answer)).searchNearbyThenByKeyword(region, OVERTURE_DATASET, "Qwokka", KeywordRegionMode.NONE)
            assertEquals(listOf(Via.KEYWORD to "Qwokka Bar", Via.TILES to "Quokka Lounge"), result.places.map { it.via to it.name })
        }

        @Test
        fun `keeps the nearby attribution entry when both halves carry the same collection`() = runTest {
            val tile = buildJsonObject { put("records", JsonArray(emptyList())); put("attribution", JsonArray(listOf(entry("from the tiles")))) }
            val answer = buildJsonObject {
                put("places", buildJsonArray {
                    add(buildJsonObject {
                        put("value", buildJsonObject { put("origin", "urn:atgeo:overturemaps.org:place:k"); put("name", "Kumquat"); put("locations", loc) })
                        put("attribution", JsonArray(listOf(entry("from the keyword answer"))))
                    })
                })
            }
            val result = client(RawServer(listOf(tileUrl to tile), answer)).searchNearbyThenByKeyword(region, OVERTURE_DATASET, "Kumquat", KeywordRegionMode.NONE)
            assertEquals(listOf("from the tiles"), result.attribution.map { it.attribution })
        }

        @Test
        fun `a caller's cancellation reaches the keyword request, which is then cancelled`() = runTest {
            val started = CompletableDeferred<Unit>()
            val tile = buildJsonObject { put("records", JsonArray(emptyList())) }
            val raw = RawServer(listOf(tileUrl to tile), null) { started.complete(Unit); awaitCancellation() }
            val call = async { client(raw).searchNearbyThenByKeyword(region, OVERTURE_DATASET, "Kumquat", KeywordRegionMode.NONE) }
            started.await()
            call.cancel(CancellationException("caller gave up"))
            assertFailsWith<CancellationException> { call.await() }
        }
    }
}
