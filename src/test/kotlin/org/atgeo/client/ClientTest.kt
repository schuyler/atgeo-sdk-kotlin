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

private val OVERTURE_PLACE = Attribution("overturemaps.org", "place")

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
            assertEquals(PlaceRecord(origin, value, 80, listOf(OVERTURE_PLACE)), client(fake()).getPlace(origin))
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
            fresh.searchPlaces(Region.Bbox(-122.40, 37.79, -122.39, 37.80), OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            val before = fake.requests.size
            assertEquals(network, fresh.getPlace(origin))
            assertEquals(before, fake.requests.size)
        }

        @Test
        fun `makes no request for the origin of a hit from an earlier search`() = runTest {
            val fake = fake()
            val client = client(fake)
            val places = client.searchPlaces(Region.Bbox(-122.40, 37.79, -122.39, 37.80), OVERTURE_DATASET, collection = OVERTURE_COLLECTION).places
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
            assertEquals(PlaceRecord("https://example.org/r/u", uriOnly, 4, listOf(OVERTURE_PLACE)), client.getPlace(places[0].origin!!))
        }

        // getPlace carries attribution as served (contract §9): from the cache, that of the tile that held the
        // record; from the network, the response's. Neither is merged, deduplicated or sorted.
        private val references = listOf(
            collectionOf("z.example", "c"),
            collectionOf("a.example", "c", "release" to JsonPrimitive("1")),
            collectionOf("a.example", "c", "release" to JsonPrimitive("2")),
        )
        private val served = listOf(
            Attribution("overturemaps.org", "place", release = "r0", licenses = listOf("https://l.example/1")),
            Attribution("z.example", "c"),
            Attribution("a.example", "c", release = "1"),
            Attribution("a.example", "c", release = "2"),
        )

        private fun withReferences() = FakeServer(
            tilesets = listOf(
                FakeTileset(
                    slug = "overture-place",
                    collections = listOf(
                        collectionOf(
                            "overturemaps.org", "place",
                            "licenses" to JsonArray(listOf(JsonPrimitive("https://l.example/1"))), "release" to JsonPrimitive("r0"),
                        ),
                    ),
                    references = references,
                    tiles = listOf(FakeTile(tileUrl("a"), listOf(record(value, 80)))),
                ),
            ),
        )

        @Test
        fun `returns the tile's attribution from the cache in served order, duplicates included, unsorted`() = runTest {
            val fake = withReferences()
            val client = client(fake)
            client.searchPlaces(Region.Bbox(-122.40, 37.79, -122.39, 37.80), OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            fake.goOffline()
            assertEquals(served, client.getPlace(origin).attribution)
        }

        @Test
        fun `returns the response's attribution from the network in served order, duplicates included, unsorted`() = runTest {
            val fake = withReferences()
            val answer = client(fake).getPlace(origin)
            assertTrue(fake.requests.any { "org.atgeo.getPlace" in it })
            assertEquals(served, answer.attribution)
        }

        @Test
        fun `returns the same attribution from the cache and the network`() = runTest {
            val fake = withReferences()
            val network = client(fake).getPlace(origin)
            val fresh = client(fake)
            fresh.searchPlaces(Region.Bbox(-122.40, 37.79, -122.39, 37.80), OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertEquals(network, fresh.getPlace(origin))
        }

        @Test
        fun `an answer's attribution cannot be changed, and changing it attempts nothing cached`() = runTest {
            val fake = withReferences()
            val client = client(fake)
            val search = Region.Bbox(-122.40, 37.79, -122.39, 37.80)
            val first = client.searchPlaces(search, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            val original = first.attribution.toList()
            assertTrue(original.any { it.licenses != null })
            fake.goOffline()
            for (answer in listOf(client.getPlace(origin).attribution, first.attribution)) {
                for (entry in answer.toList()) tryToChange(entry.licenses)
                tryToChange(answer)
            }
            assertEquals(original, client.searchPlaces(search, OVERTURE_DATASET, collection = OVERTURE_COLLECTION).attribution)
            assertEquals(served, client.getPlace(origin).attribution)
        }

        @Test
        fun `a network answer's attribution cannot be changed either`() = runTest {
            val client = client(withReferences())
            val answer = client.getPlace(origin).attribution
            assertTrue(answer.any { it.licenses != null })
            for (entry in answer.toList()) tryToChange(entry.licenses)
            tryToChange(answer)
            // The answer is the app's own, and a repeat of the call (the network again, as the cache holds no tile) is unaffected.
            assertEquals(served, client.getPlace(origin).attribution)
        }

        @TestFactory
        fun `gives an empty attribution from a cached tile whose attribution is absent or not an array`(): List<DynamicTest> =
            listOf("absent" to "", "an object" to ""","attribution": {}""", "null" to ""","attribution": null""", "a string" to ""","attribution": "x"""").map { (name, extra) ->
                dynamicTest("gives an empty attribution from a tile with an attribution that is $name") {
                    runTest {
                        val plain = place("x", "Plain", listOf(point(37.775, -122.415)))
                        val server = FakeServer(
                            responses = mapOf(
                                Xrpc(DEFAULT_SERVER, { error("unused") }).url(
                                    "org.atgeo.getTiles",
                                    listOf("dataset" to "overturemaps.org", "collection" to "place", "bbox" to "-122.42,37.77,-122.41,37.78"),
                                ) to
                                    HttpResponse(200, """{"tiles": ["${tileUrl("raw")}"]}""".toByteArray()),
                                tileUrl("raw") to HttpResponse(200, """{"records": [{"value": $plain, "importance": 3}]$extra}""".toByteArray()),
                            ),
                        )
                        val client = client(server)
                        val places = client.searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION).places
                        assertEquals(1, places.size)
                        assertNull(places[0].collection)
                        val before = server.requests.size
                        assertEquals(PlaceRecord(placeOrigin("x"), plain, 3, emptyList()), client.getPlace(placeOrigin("x")))
                        assertEquals(before, server.requests.size)
                    }
                }
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
                    maxTiles = 50,
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
        fun `describeTileset passes the overview through`() = runTest {
            val withOverview = osm.copy(overview = buildJsonObject { put("records", 1234) })
            val tileset = client(FakeServer(tilesets = listOf(withOverview))).describeTileset("osm")
            assertEquals(Tileset.Overview(1234), tileset.overview)
            assertEquals(
                Tileset(
                    slug = "osm",
                    collections = listOf(
                        CollectionInfo(dataset = "openstreetmap.org", collection = "way", recordCount = 3, categories = listOf(CollectionInfo.CategoryCount("cafe", 2))),
                    ),
                    references = listOf(CollectionAttribution(dataset = "openstreetmap.org", collection = "node", licenses = listOf("https://opendatacommons.org/licenses/odbl/"))),
                    overview = Tileset.Overview(1234),
                ),
                tileset,
            )
        }

        @Test
        fun `describeTileset has no overview when the tileset serves none`() = runTest {
            assertNull(client(fake()).describeTileset("osm").overview)
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
            val result = setup().searchPlaces(circle, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
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
            assertEquals(0.0, setup().searchPlaces(circle, OVERTURE_DATASET, collection = OVERTURE_COLLECTION).places.find { it.origin == placeOrigin("division") }?.distance)
        }

        @Test
        fun `returns a numeric score with text`() = runTest {
            val result = setup().searchPlaces(circle, OVERTURE_DATASET, text = "Zanzibar Cafe", collection = OVERTURE_COLLECTION)
            assertCloseTo(1.0, result.places.find { it.origin == placeOrigin("cafe") }?.score, 2)
            assertTrue(result.places.all { it.score != null })
        }

        @Test
        fun `treats text that normalizes to empty as no text`() = runTest {
            val client = setup()
            val none = client.searchPlaces(circle, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            val blank = client.searchPlaces(circle, OVERTURE_DATASET, text = "   ", collection = OVERTURE_COLLECTION)
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
            return client(server).searchPlaces(region, OVERTURE_DATASET, text = text, sort = sort, collection = OVERTURE_COLLECTION).places.map { it.origin.toString().removePrefix(placeOrigin("")) }
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

        // The type makes a search without a dataset a compile error (contract §4.1). What is left to test is the
        // one way past the type, a caller that passes null (Java, reflection): the Kotlin compiler emits no null check
        // for the parameters of a suspend function, so the client checks for itself and throws the argument error.
        private fun searchViaReflection(client: AtgeoClient, region: Region, dataset: String?, collection: String?): Any? {
            val method = AtgeoClient::class.java.methods.single { it.name == "searchPlaces" }
            val continuation = object : kotlin.coroutines.Continuation<Any?> {
                override val context = kotlin.coroutines.EmptyCoroutineContext

                override fun resumeWith(result: Result<Any?>) {}
            }
            return method.invoke(client, region, dataset, null, collection, SortMode.FUSED, continuation)
        }

        @TestFactory
        fun `rejects a null dataset with IllegalArgumentException, before any request and before the region is validated`(): List<DynamicTest> =
            listOf("a valid region" to region, "an invalid region" to Region.Bbox(0.0, 10.0, 1.0, 5.0)).flatMap { (name, r) ->
                listOf(null, "place").map { collection ->
                    dynamicTest("$name, collection $collection") {
                        runTest {
                            val fake = fake()
                            val error = assertFailsWith<java.lang.reflect.InvocationTargetException> { searchViaReflection(client(fake), r, null, collection) }
                            assertTrue(error.targetException is IllegalArgumentException, "${error.targetException}")
                            assertTrue("dataset" in error.targetException.message.orEmpty(), "${error.targetException.message}")
                            assertEquals(emptyList(), fake.requests)
                        }
                    }
                }
            }

        @Test
        fun `sends an empty dataset to the server, which answers for itself`() = runTest {
            val fake = fake()
            assertFailsWith<AtgeoException> { client(fake).searchPlaces(region, "") }
            val lists = tileListRequests(fake)
            assertEquals(1, lists.size)
            assertEquals("", lists[0]["dataset"])
        }

        @Test
        fun `sends an empty dataset with a collection too`() = runTest {
            val fake = fake()
            assertFailsWith<AtgeoException> { client(fake).searchPlaces(region, "", collection = "place") }
            assertEquals("", tileListRequests(fake).single()["dataset"])
        }

        @Test
        fun `gives each hit the collection its origin names, by prefix, and null to a hit keyed by uri`() = runTest {
            val hits = client(fake()).searchPlaces(region, "openstreetmap.org").places
            assertEquals(
                mapOf(
                    "urn:atgeo:openstreetmap.org:node:1" to CollectionKey("openstreetmap.org", "node"),
                    "urn:atgeo:openstreetmap.org:way:2" to CollectionKey("openstreetmap.org", "way"),
                    "urn:atgeo:openstreetmap.org:relation:3" to CollectionKey("openstreetmap.org", "relation"),
                    "https://example.org/r/u" to null,
                ),
                hits.associate { it.origin!! to it.collection },
            )
        }

        @Test
        fun `gives way and wayside hits their own collections`() = runTest {
            val fake = FakeServer(
                tilesets = listOf(
                    FakeTileset(
                        slug = "osm",
                        collections = listOf("way", "wayside").map { collectionOf("openstreetmap.org", it) },
                        tiles = listOf(FakeTile(tileUrl("osm"), listOf(record(osm("way", "2", 37.772)), record(osm("wayside", "9", 37.773))))),
                    ),
                ),
            )
            val hits = client(fake).searchPlaces(region, "openstreetmap.org").places
            assertEquals(
                mapOf("urn:atgeo:openstreetmap.org:way:2" to "way", "urn:atgeo:openstreetmap.org:wayside:9" to "wayside"),
                hits.associate { it.origin!! to it.collection?.collection },
            )
        }

        @Test
        fun `returns the union of the tile's attribution, references included, sorted`() = runTest {
            val fake = FakeServer(
                tilesets = listOf(
                    FakeTileset(
                        slug = "osm",
                        collections = listOf(
                            collectionOf(
                                "openstreetmap.org", "way",
                                "release" to JsonPrimitive("2026-01-01"),
                                "licenses" to JsonArray(listOf(JsonPrimitive("https://opendatacommons.org/licenses/odbl/"))),
                            ),
                            collectionOf("openstreetmap.org", "relation"),
                        ),
                        references = listOf(collectionOf("openstreetmap.org", "node", "source" to JsonPrimitive("https://www.openstreetmap.org"))),
                        tiles = listOf(FakeTile(tileUrl("osm"), listOf(record(osm("way", "2", 37.772))))),
                    ),
                ),
            )
            val result = client(fake).searchPlaces(region, "openstreetmap.org", collection = "way")
            assertEquals(
                listOf(
                    Attribution("openstreetmap.org", "node", source = "https://www.openstreetmap.org"),
                    Attribution("openstreetmap.org", "relation"),
                    Attribution("openstreetmap.org", "way", release = "2026-01-01", licenses = listOf("https://opendatacommons.org/licenses/odbl/")),
                ),
                result.attribution,
            )
        }

        @Test
        fun `reads the origin alone for the filter and the hit's collection, never the uri`() = runTest {
            val byUri = place("7", "By uri", listOf(point(37.775, -122.415)), noOrigin)
            val fake = FakeServer(
                tilesets = listOf(
                    FakeTileset(
                        slug = "osm",
                        collections = listOf(collectionOf("openstreetmap.org", "way")),
                        tiles = listOf(FakeTile(tileUrl("osm"), listOf(record(osm("way", "2", 37.772)), record(byUri, uri = "urn:atgeo:openstreetmap.org:way:7")))),
                    ),
                ),
            )
            val client = client(fake)
            assertEquals(
                listOf("urn:atgeo:openstreetmap.org:way:2"),
                client.searchPlaces(region, "openstreetmap.org", collection = "way").places.map { it.origin },
            )
            val whole = client.searchPlaces(region, "openstreetmap.org").places
            assertEquals(
                mapOf("urn:atgeo:openstreetmap.org:way:2" to CollectionKey("openstreetmap.org", "way"), "urn:atgeo:openstreetmap.org:way:7" to null),
                whole.associate { it.origin!! to it.collection },
            )
        }

        @Test
        fun `matches the prefix at the start of the origin, not anywhere in it`() = runTest {
            val inside = place("5", "Inside", listOf(point(37.775, -122.415)), mapOf("origin" to JsonPrimitive("prefix:urn:atgeo:openstreetmap.org:way:5")))
            val fake = FakeServer(
                tilesets = listOf(
                    FakeTileset(
                        slug = "osm",
                        collections = listOf(collectionOf("openstreetmap.org", "way")),
                        tiles = listOf(FakeTile(tileUrl("osm"), listOf(record(osm("way", "2", 37.772)), record(inside)))),
                    ),
                ),
            )
            val client = client(fake)
            assertEquals(
                listOf("urn:atgeo:openstreetmap.org:way:2"),
                client.searchPlaces(region, "openstreetmap.org", collection = "way").places.map { it.origin },
            )
            assertEquals(
                mapOf("urn:atgeo:openstreetmap.org:way:2" to "way", "prefix:urn:atgeo:openstreetmap.org:way:5" to null),
                client.searchPlaces(region, "openstreetmap.org").places.associate { it.origin!! to it.collection?.collection },
            )
        }

        @Test
        fun `a hit's collection is just the two names, not the attribution entry`() = runTest {
            val hit = client(fake()).searchPlaces(region, "openstreetmap.org", collection = "way").places.single()
            assertEquals(CollectionKey("openstreetmap.org", "way"), hit.collection)
            assertEquals("CollectionKey(dataset=openstreetmap.org, collection=way)", hit.collection.toString())
        }
    }
}
