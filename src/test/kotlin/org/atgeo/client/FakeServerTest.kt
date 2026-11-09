package org.atgeo.client

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val TILE = "https://tiles.example/a.json"
private val ok = place("a", "Alpha", listOf(point(37.772, -122.412)))

private fun build(records: List<JsonObject>, slug: String? = "overture-place") =
    FakeServer(tilesets = listOf(overture(listOf(FakeTile(TILE, records))).copy(slug = slug)))

class FakeServerTest {
    @Nested
    @DisplayName("fake server configuration")
    inner class Configuration {
        @Test
        fun `accepts a valid configuration`() {
            build(listOf(record(ok, 3)))
        }

        @Test
        fun `rejects a record value missing its name`() {
            val nameless = JsonObject(ok - "name")
            val error = assertFailsWith<IllegalArgumentException> { build(listOf(record(nameless))) }
            assertTrue("name" in error.message.orEmpty())
        }

        @Test
        fun `rejects a tile record missing its value`() {
            val error = assertFailsWith<IllegalArgumentException> { build(listOf(buildJsonObject { put("importance", 3) })) }
            assertTrue("\"value\"" in error.message.orEmpty())
        }

        @Test
        fun `rejects a float importance`() {
            val float = buildJsonObject {
                put("value", ok)
                put("importance", 0.5)
            }
            val error = assertFailsWith<IllegalArgumentException> { build(listOf(float)) }
            assertTrue("integer" in error.message.orEmpty())
        }

        @Test
        fun `rejects a descriptor missing its slug`() {
            val error = assertFailsWith<IllegalArgumentException> { build(emptyList(), slug = null) }
            assertTrue("slug" in error.message.orEmpty())
        }
    }

    @Nested
    @DisplayName("fake server requests")
    inner class Requests {
        @Test
        fun `answers 400 InvalidRequest to getTiles without a dataset`() = runTest {
            val response = build(listOf(record(ok))).get("$DEFAULT_SERVER/xrpc/org.atgeo.getTiles?bbox=-122.42,37.77,-122.41,37.78")
            assertEquals(400, response.status)
            assertEquals("InvalidRequest", parseBody(response.body).let { (it as JsonObject)["error"].stringOrNull() })
        }

        @Test
        fun `answers 400 InvalidRequest to getPlace with a non-URI uri`() = runTest {
            val response = build(listOf(record(ok))).get("$DEFAULT_SERVER/xrpc/org.atgeo.getPlace?uri=not%20a%20uri")
            assertEquals(400, response.status)
        }

        @Test
        fun `serves tile bodies with publishedAt, attribution and records`() = runTest {
            val body = parseBody(build(listOf(record(ok, 3))).get(TILE).body) as JsonObject
            assertEquals(listOf("attribution", "publishedAt", "records"), body.keys.sorted())
            assertEquals(JsonArray(listOf(buildJsonObject { put("value", ok); put("importance", 3) })), body["records"])
        }

        @Test
        fun `answers getPlace with exactly uri, url, importance, value and attribution`() = runTest {
            val origin = ok["origin"].stringOrNull()!!
            val uri = "uri=${java.net.URLEncoder.encode(origin, "UTF-8")}"
            val body = parseBody(build(listOf(record(ok, 3))).get("$DEFAULT_SERVER/xrpc/org.atgeo.getPlace?$uri").body) as JsonObject
            assertEquals(listOf("attribution", "importance", "uri", "url", "value"), body.keys.sorted())
            assertEquals(JsonPrimitive(origin), body["uri"])
            assertEquals(ok, body["value"])
            assertEquals(JsonPrimitive(3), body["importance"])
            assertEquals(JsonArray(listOf(collectionOf("overturemaps.org", "place"))), body["attribution"])
            val bare = parseBody(build(listOf(record(ok))).get("$DEFAULT_SERVER/xrpc/org.atgeo.getPlace?$uri").body) as JsonObject
            assertEquals(listOf("attribution", "uri", "url", "value"), bare.keys.sorted())
        }

        @Test
        fun `emits a tileset's overview in its descriptor`() = runTest {
            val fake = FakeServer(
                tilesets = listOf(overture(emptyList()).copy(overview = buildJsonObject { put("records", 7) })),
            )
            val body = parseBody(fake.get("$DEFAULT_SERVER/xrpc/org.atgeo.describeTileset?slug=overture-place").body) as JsonObject
            assertEquals(buildJsonObject { put("records", 7) }, body["overview"])
        }

        @Test
        fun `answers describeService with a did and tilesets`() = runTest {
            val body = parseBody(build(emptyList()).get("$DEFAULT_SERVER/xrpc/org.atgeo.describeService").body)
            assertEquals(
                buildJsonObject {
                    put("did", "did:web:places.atgeo.org")
                    put("maxTiles", 50)
                    put(
                        "tilesets",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("slug", "overture-place")
                                    put("collections", JsonArray(listOf(collectionOf("overturemaps.org", "place"))))
                                },
                            ),
                        ),
                    )
                },
                body,
            )
        }

        // Not in the reference suite: the request checks the lexicon validator gives it for free.
        @Test
        fun `answers 400 InvalidRequest to an unknown parameter and to an off-grid bbox`() = runTest {
            val fake = build(emptyList())
            assertEquals(400, fake.get("$DEFAULT_SERVER/xrpc/org.atgeo.describeService?q=1").status)
            assertEquals(400, fake.get("$DEFAULT_SERVER/xrpc/org.atgeo.getTiles?dataset=x&bbox=-122.421,37.77,-122.41,37.78").status)
            assertEquals(JsonPrimitive("PlaceNotFound"), (parseBody(fake.get("$DEFAULT_SERVER/xrpc/org.atgeo.getPlace?uri=urn%3Aatgeo%3Ax%3Ay%3Az").body) as JsonObject)["error"])
        }
    }

    @Nested
    @DisplayName("fake server searchPlaces")
    inner class SearchPlacesAnswers {
        private val records = listOf(
            record(place("a", "Starbucks", listOf(point(37.772, -122.412))), 5),
            record(place("b", "McDonald's", listOf(point(37.773, -122.413)), mapOf("variants" to JsonArray(listOf(buildJsonObject { put("name", "Mickey D's") })))), 9),
        )
        private fun search(query: String): Pair<Int, JsonObject> {
            val r = kotlinx.coroutines.runBlocking { build(records).get("$DEFAULT_SERVER/xrpc/org.atgeo.searchPlaces?$query") }
            return r.status to (kotlinx.serialization.json.Json.parseToJsonElement(r.body.decodeToString()) as JsonObject)
        }
        private val base = "dataset=overturemaps.org&q=star"
        private fun uris(body: JsonObject) = (body["places"] as JsonArray).map { ((it as JsonObject)["uri"] as JsonPrimitive).content }

        @Test
        fun `matches each keyword word as a prefix of a word of one name`() {
            assertEquals(listOf(placeOrigin("a")), uris(search(base).second))
            for (q in listOf("mcdonalds", "mickey", "D%27s%20mick")) assertEquals(1, uris(search("dataset=overturemaps.org&q=$q").second).size)
            assertEquals(emptyList(), uris(search("dataset=overturemaps.org&q=mcd%20starb").second))
        }

        @Test
        fun `answers 400 KeywordRequired to a keyword with no word of two characters`() {
            for (q in listOf("a", "%ED%95%98", "%E0%A4%95%E0%A4%BE", "a%20b%27", "%20%20")) {
                val (status, body) = search("dataset=overturemaps.org&q=$q")
                assertEquals(400 to "KeywordRequired", status to (body["error"] as JsonPrimitive).content, q)
            }
        }

        @Test
        fun `answers the bbox errors of the server`() {
            for ((bbox, error) in listOf(
                "-76.0,39,-75,40" to "BboxTooPrecise", "-76,39,-75,40.5" to "BboxTooPrecise", "1e1,39,20,40" to "BboxTooPrecise",
                "-76,39,-75" to "InvalidBbox", "a,39,-75,40" to "InvalidBbox", "-76,40,-75,40" to "InvalidBbox",
                "-75,39,-76,40" to "InvalidBbox", "10,39,10,40" to "InvalidBbox", "180,39,-180,40" to "InvalidBbox",
                "0,39,-1,40" to "InvalidBbox", "-181,39,-75,40" to "InvalidBbox", "-76,39,-75,91" to "InvalidBbox",
            )) assertEquals(error, (search("$base&bbox=$bbox").second["error"] as JsonPrimitive).content, bbox)
            for (bbox in listOf("-76,39,-75,40", "76.,39,77,40", "170,39,-170,40", "-180,39,180,40")) assertEquals(200, search("$base&bbox=$bbox").first, bbox)
        }

        @Test
        fun `checks the precision, then the bbox, then the dataset, then the keyword`() {
            fun error(q: String) = (search(q).second["error"] as JsonPrimitive).content
            assertEquals("BboxTooPrecise", error("dataset=nope&q=a&bbox=1.5,2,1,3"))
            assertEquals("InvalidBbox", error("dataset=nope&q=a&bbox=2,2,1,3"))
            assertEquals("DatasetNotFound", error("dataset=nope&q=a"))
            assertEquals("CollectionNotFound", error("dataset=overturemaps.org&collection=nope&q=a"))
            assertEquals("KeywordRequired", error("dataset=overturemaps.org&q=a"))
        }

        @Test
        fun `answers getTiles BboxTooPrecise for a coordinate finer than two decimals`() {
            for (bbox in listOf("-122.4194,37.77,-122.41,37.78", "-122.42,37.77,-122.41,37.7801")) {
                val r = kotlinx.coroutines.runBlocking { build(records).get("$DEFAULT_SERVER/xrpc/org.atgeo.getTiles?dataset=overturemaps.org&bbox=$bbox") }
                assertEquals(400, r.status)
                assertTrue("BboxTooPrecise" in r.body.decodeToString())
            }
        }
    }
}
