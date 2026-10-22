package org.atgeo.client

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val region = Region.Bbox(-122.42, 37.77, -122.41, 37.78)
private val noOrigin: Map<String, JsonElement?> = mapOf("origin" to null)

private fun nsid(url: String) = endpointOf(url).substringAfterLast('/')

private fun assertCloseTo(expected: Double, actual: Double?, digits: Int) {
    assertNotNull(actual)
    assertTrue(abs(actual - expected) < Math.pow(10.0, -digits.toDouble()) / 2, "expected $expected, got $actual")
}

// The two import.meta.glob suites ("retired wire names", "universal runtime (R9)") scan the
// TypeScript source and are not ported; the animalsniffer check is their counterpart here.
class ClientTest {
    @Nested
    @DisplayName("getPlace")
    inner class GetPlace {
        private val value = place(
            "r1",
            "Ferry Building",
            listOf(point(37.7955, -122.3937)),
            mapOf(
                "attributes" to buildJsonObject { put("kind", "landmark") },
                "futureKey" to buildJsonObject { put("nested", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))) },
            ),
        )
        private val origin = placeOrigin("r1")
        private val noImportance = place("r2", "No Importance", listOf(point(37.7955, -122.3937)))

        private fun fake() = FakeServer(tilesets = listOf(overture(listOf(FakeTile(tileUrl("a"), listOf(record(value, 80), record(noImportance)))))))

        @Test
        fun `sends one getPlace request with the origin as uri on a cold client, and no describeService`() = runTest {
            val fake = fake()
            client(fake).getPlace(origin)
            assertEquals(1, fake.requests.size)
            assertEquals("$DEFAULT_SERVER/xrpc/org.atgeo.getPlace", endpointOf(fake.requests[0]))
            assertEquals(origin, paramsOf(fake.requests[0])["uri"])
            assertFalse("org.atgeo.describeService" in fake.requests.map(::nsid))
        }

        @Test
        fun `returns the value unmodified with importance beside it`() = runTest {
            assertEquals(PlaceRecord(origin, value, 80), client(fake()).getPlace(origin))
        }

        @Test
        fun `returns a null importance when the server gives none`() = runTest {
            assertNull(client(fake()).getPlace(placeOrigin("r2")).importance)
        }

        @Test
        fun `returns the same answer from the cache and the network`() = runTest {
            val fake = fake()
            val network = client(fake).getPlace(origin)
            val fresh = client(fake)
            fresh.searchPlaces(Region.Bbox(-122.40, 37.79, -122.39, 37.80))
            val before = fake.requests.size
            assertEquals(network, fresh.getPlace(origin))
            assertEquals(before, fake.requests.size)
        }

        @Test
        fun `makes no request for the origin of a hit from an earlier search`() = runTest {
            val fake = fake()
            val client = client(fake)
            val places = client.searchPlaces(Region.Bbox(-122.40, 37.79, -122.39, 37.80)).places
            val before = fake.requests.size
            client.getPlace(places[0].origin!!)
            assertEquals(before, fake.requests.size)
        }

        @Test
        fun `resolves a hit identified by a record uri from the cache`() = runTest {
            val uriOnly = place("u", "By uri", listOf(point(37.775, -122.415)), noOrigin)
            val fake = FakeServer(tilesets = listOf(overture(listOf(FakeTile(tileUrl("a"), listOf(record(uriOnly, 4, "https://example.org/r/u")))))))
            val client = client(fake)
            val places = client.searchPlaces(region, dataset = "overturemaps.org").places
            assertEquals("https://example.org/r/u", places[0].origin)
            fake.goOffline()
            assertEquals(PlaceRecord("https://example.org/r/u", uriOnly, 4), client.getPlace(places[0].origin!!))
        }

        @TestFactory
        fun `rejects an unknown origin with PlaceNotFound`(): List<DynamicTest> =
            listOf(placeOrigin("nope"), "https://example.org/not-an-origin").map { unknown ->
                dynamicTest("rejects $unknown with PlaceNotFound") {
                    runTest {
                        val error = assertFailsWith<AtgeoException> { client(fake()).getPlace(unknown) }
                        assertEquals("PlaceNotFound", error.code)
                    }
                }
            }
    }

    @Nested
    @DisplayName("describeService and describeTileset")
    inner class Describe {
        private val osm = FakeTileset(
            slug = "osm",
            collections = listOf(
                collectionOf(
                    "openstreetmap.org",
                    "way",
                    "recordCount" to JsonPrimitive(3),
                    "categories" to JsonArray(listOf(buildJsonObject { put("value", "cafe"); put("count", 2) })),
                ),
            ),
            references = listOf(
                collectionOf("openstreetmap.org", "node", "licenses" to JsonArray(listOf(JsonPrimitive("https://opendatacommons.org/licenses/odbl/")))),
            ),
            tiles = emptyList(),
        )

        private fun fake() = FakeServer(tilesets = listOf(overture(emptyList()), osm))

        @Test
        fun `describeService returns the did and the configured tilesets`() = runTest {
            val service = client(fake()).describeService()
            assertEquals(
                ServiceDescription(
                    did = "did:web:places.atgeo.org",
                    tilesets = listOf(
                        ServiceDescription.ServedTileset("overture-place", listOf(CollectionRef("overturemaps.org", "place"))),
                        ServiceDescription.ServedTileset("osm", listOf(CollectionRef("openstreetmap.org", "way"))),
                    ),
                ),
                service,
            )
            assertEquals(listOf("overture-place", "osm"), service.tilesets.map { it.slug })
        }

        @Test
        fun `describeTileset returns the descriptor`() = runTest {
            val tileset = client(fake()).describeTileset("osm")
            assertEquals(
                Tileset(
                    slug = "osm",
                    collections = listOf(
                        CollectionInfo(dataset = "openstreetmap.org", collection = "way", recordCount = 3, categories = listOf(CollectionInfo.CategoryCount("cafe", 2))),
                    ),
                    references = listOf(CollectionAttribution(dataset = "openstreetmap.org", collection = "node", licenses = listOf("https://opendatacommons.org/licenses/odbl/"))),
                ),
                tileset,
            )
            assertEquals("osm", tileset.slug)
        }

        @Test
        fun `describeTileset asks the server each time`() = runTest {
            val fake = fake()
            val client = client(fake)
            client.describeTileset("osm")
            client.describeTileset("osm")
            assertEquals(2, fake.requests.size)
        }

        @Test
        fun `describeTileset rejects an unknown slug with TilesetNotFound`() = runTest {
            val error = assertFailsWith<AtgeoException> { client(fake()).describeTileset("nope") }
            assertEquals("TilesetNotFound", error.code)
        }
    }

    @Nested
    @DisplayName("searchPlaces results")
    inner class SearchPlacesResults {
        private val records = listOf(
            record(
                place(
                    "cafe",
                    "Zanzibar Cafe",
                    listOf(point(0.001, 0.0)),
                    mapOf(
                        "futureKey" to buildJsonObject { put("a", 1) },
                        "variants" to JsonArray(listOf(buildJsonObject { put("name", "ZC") })),
                    ),
                ),
                2,
            ),
            record(place("shop", "Corner Shop", listOf(point(0.002, 0.0))), 9),
            record(place("division", "Some Division", listOf(bboxLocation(0.01, -0.01, 0.01, -0.01))), 1),
        )
        private val circle = Region.Circle(0.0, 0.0, 2000.0)

        private fun TestScope.setup() = client(FakeServer(tilesets = listOf(overture(listOf(FakeTile(tileUrl("a"), records))))))

        @Test
        fun `returns origin, the wire place, importance, meters distance and null score without text`() = runTest {
            val result = setup().searchPlaces(circle)
            val cafe = assertNotNull(result.places.find { it.origin == placeOrigin("cafe") })
            assertEquals(records[0]["value"], cafe.place)
            assertFalse("importance" in cafe.place)
            // The reference also asserts the hit has no "uri" property; SearchHit has a fixed shape.
            assertEquals(2L, cafe.importance)
            assertCloseTo(111.195, cafe.distance, 1)
            assertTrue(result.places.all { it.score == null })
        }

        @Test
        fun `gives distance 0 to a bbox containing the center`() = runTest {
            assertEquals(0.0, setup().searchPlaces(circle).places.find { it.origin == placeOrigin("division") }?.distance)
        }

        @Test
        fun `returns a numeric score with text`() = runTest {
            val result = setup().searchPlaces(circle, text = "Zanzibar Cafe")
            assertCloseTo(1.0, result.places.find { it.origin == placeOrigin("cafe") }?.score, 2)
            assertTrue(result.places.all { it.score != null })
        }

        @Test
        fun `treats text that normalizes to empty as no text`() = runTest {
            val client = setup()
            val none = client.searchPlaces(circle)
            val blank = client.searchPlaces(circle, text = "   ")
            assertTrue(blank.places.isNotEmpty())
            assertEquals(none, blank)
            assertTrue(blank.places.all { it.score == null })
        }
    }

    @Nested
    @DisplayName("ordering among places containing the center")
    inner class OrderingContainingCenter {
        private suspend fun TestScope.ids(
            values: List<Pair<JsonObject, Long?>>,
            region: Region,
            text: String? = null,
            sort: SortMode = SortMode.FUSED,
        ): List<String> {
            val server = FakeServer(tilesets = listOf(overture(listOf(FakeTile(tileUrl("a"), values.map { (v, i) -> record(v, i) })))))
            return client(server).searchPlaces(region, text = text, sort = sort).places.map { it.origin.toString().removePrefix(placeOrigin("")) }
        }

        @Test
        fun `puts the smaller box first when the center sits on the antimeridian seam`() = runTest {
            // Center is exactly (45, 180). The small box reaches it as lon -180 / 180.
            val small = place("small", "Small", listOf(bboxLocation(50.0, 40.0, -170.0, -180.0)))
            val large = place("large", "Large", listOf(bboxLocation(55.0, 35.0, -160.0, 160.0)))
            val order = ids(listOf(small to 1L, large to 9L), Region.Bbox(170.0, 40.0, -170.0, 50.0), sort = SortMode.DISTANCE)
            assertEquals(listOf("small", "large"), order)
        }

        @TestFactory
        fun `leaves ties to the origin whatever the sizes`(): List<DynamicTest> = listOf(SortMode.FUSED, SortMode.SIMILARITY).map { sort ->
            dynamicTest("leaves ties to the origin under ${sort.name.lowercase()} sort, whatever the sizes") {
                runTest {
                    val large = place("a-large", "Same", listOf(bboxLocation(50.0, 40.0, 15.0, 5.0)))
                    val small = place("b-small", "Same", listOf(bboxLocation(46.0, 44.0, 11.0, 9.0)))
                    val order = ids(listOf(small to 5L, large to 5L), Region.Circle(45.0, 10.0, 1000.0), text = "Same", sort = sort)
                    assertEquals(listOf("a-large", "b-small"), order)
                }
            }
        }

        @Test
        fun `puts a point at the center before a more important containing box`() = runTest {
            val order = ids(
                listOf(place("box", "Box", listOf(bboxLocation(46.0, 44.0, 11.0, 9.0))) to 9L, place("pin", "Pin", listOf(point(45.0, 10.0))) to 1L),
                Region.Circle(45.0, 10.0, 1000.0),
                sort = SortMode.DISTANCE,
            )
            assertEquals(listOf("pin", "box"), order)
        }
    }

    @Nested
    @DisplayName("datasets and collections")
    inner class DatasetsAndCollections {
        private fun osm(kind: String, id: String, lat: Double) =
            place(id, "$kind $id", listOf(point(lat, -122.415)), mapOf("origin" to JsonPrimitive("urn:atgeo:openstreetmap.org:$kind:$id")))

        private val osmTileset = FakeTileset(
            slug = "osm",
            collections = listOf("node", "way", "relation").map { collectionOf("openstreetmap.org", it) },
            tiles = listOf(
                FakeTile(
                    tileUrl("osm"),
                    listOf(
                        record(osm("node", "1", 37.771)),
                        record(osm("way", "2", 37.772)),
                        record(osm("relation", "3", 37.773)),
                        record(place("u", "By uri", listOf(point(37.774, -122.415)), noOrigin), uri = "https://example.org/r/u"),
                    ),
                ),
            ),
        )

        private fun fake() = FakeServer(tilesets = listOf(overture(emptyList()), osmTileset))

        private fun params(fake: FakeServer) = paramsOf(fake.requests[0])

        private fun tileListRequests(fake: FakeServer) = fake.requests.filter { "org.atgeo.getTiles" in it }.map(::paramsOf)

        @Test
        fun `keys the tile list on dataset and collection`() = runTest {
            val fake = fake()
            val client = client(fake)
            client.searchPlaces(region, dataset = "openstreetmap.org", collection = "way")
            client.searchPlaces(region, dataset = "openstreetmap.org")
            client.searchPlaces(region, dataset = "overturemaps.org")
            assertEquals(
                listOf("openstreetmap.org|way", "openstreetmap.org|null", "overturemaps.org|null"),
                tileListRequests(fake).map { "${it["dataset"]}|${it["collection"]}" },
            )
        }

        @Test
        fun `matches the collection, not a collection sharing its prefix`() = runTest {
            val fake = FakeServer(
                tilesets = listOf(
                    FakeTileset(
                        slug = "osm",
                        collections = listOf("way", "wayside").map { collectionOf("openstreetmap.org", it) },
                        tiles = listOf(FakeTile(tileUrl("osm"), listOf(record(osm("way", "2", 37.772)), record(osm("wayside", "9", 37.773))))),
                    ),
                ),
            )
            val places = client(fake).searchPlaces(region, dataset = "openstreetmap.org", collection = "way").places
            assertEquals(listOf("urn:atgeo:openstreetmap.org:way:2"), places.map { it.origin })
        }

        @Test
        fun `searches a whole dataset when only the dataset is named`() = runTest {
            val fake = fake()
            val places = client(fake).searchPlaces(region, dataset = "openstreetmap.org").places
            assertEquals(
                listOf(
                    "https://example.org/r/u",
                    "urn:atgeo:openstreetmap.org:node:1",
                    "urn:atgeo:openstreetmap.org:relation:3",
                    "urn:atgeo:openstreetmap.org:way:2",
                ),
                places.map { it.origin!! }.sorted(),
            )
            assertEquals("openstreetmap.org", params(fake)["dataset"])
            assertFalse("collection" in params(fake))
        }

        @Test
        fun `filters to one collection, dropping records with no origin`() = runTest {
            val fake = fake()
            val places = client(fake).searchPlaces(region, dataset = "openstreetmap.org", collection = "way").places
            assertEquals(listOf("urn:atgeo:openstreetmap.org:way:2"), places.map { it.origin })
            assertEquals("way", params(fake)["collection"])
        }

        @Test
        @DisplayName("defaults to overturemaps.org / place when nothing is named")
        fun defaultsToOverturePlace() = runTest {
            val fake = fake()
            client(fake).searchPlaces(region)
            assertEquals("overturemaps.org", params(fake)["dataset"])
            assertEquals("place", params(fake)["collection"])
        }

        @Test
        @DisplayName("takes overturemaps.org for a collection named alone")
        fun takesOvertureForACollectionAlone() = runTest {
            val fake = fake()
            runCatching { client(fake).searchPlaces(region, collection = "division") }
            assertEquals("overturemaps.org", params(fake)["dataset"])
            assertEquals("division", params(fake)["collection"])
        }
    }
}
