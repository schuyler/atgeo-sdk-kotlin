package org.atgeo.client

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun idOf(origin: String?) = origin.toString().removePrefix(placeOrigin(""))

private val noOrigin: Map<String, JsonElement?> = mapOf("origin" to null)

private suspend fun TestScope.search(records: List<JsonObject>, region: Region): List<String> {
    val server = FakeServer(tilesets = listOf(overture(listOf(FakeTile(tileUrl("t"), records.map { record(it) })))))
    return client(server).searchPlaces(region).places.map { idOf(it.origin) }.sorted()
}

private val DIVISION = collectionOf("overturemaps.org", "division")

private fun divisions(tiles: List<FakeTile>) = FakeTileset("overture-division", listOf(DIVISION), tiles = tiles)

private fun division(id: String, name: String, lat: Double = 37.775) =
    place(id, name, listOf(point(lat, -122.415)), mapOf("origin" to JsonPrimitive("urn:atgeo:overturemaps.org:division:$id")))

private fun nameOf(hit: SearchHit) = hit.name

class MergeTest {
    @Nested
    @DisplayName("deduplication")
    inner class Deduplication {
        private val first = place("dup", "First copy", listOf(point(37.775, -122.415)))
        private val second = place("dup", "Second copy", listOf(point(37.775, -122.415)))
        private val region = Region.Bbox(-122.42, 37.77, -122.41, 37.78)

        @Test
        fun `collapses a duplicate origin across tiles to one result`() = runTest {
            val server = FakeServer(
                tilesets = listOf(
                    overture(
                        listOf(
                            FakeTile(tileUrl("a"), listOf(record(first), record(place("a-only", "A", listOf(point(37.772, -122.412)))))),
                            FakeTile(tileUrl("b"), listOf(record(second), record(place("b-only", "B", listOf(point(37.773, -122.413)))))),
                        ),
                    ),
                ),
            )
            val result = client(server).searchPlaces(region)
            assertEquals(listOf("a-only", "b-only", "dup"), result.places.map { idOf(it.origin) }.sorted())
        }

        @Test
        fun `keeps the first tile's copy in the default order too`() = runTest {
            val server = FakeServer(
                tilesets = listOf(overture(listOf(FakeTile(tileUrl("a"), listOf(record(first))), FakeTile(tileUrl("b"), listOf(record(second)))))),
            )
            assertEquals(listOf("First copy"), client(server).searchPlaces(region).places.map(::nameOf))
        }

        @Test
        fun `merges a division record held by two tiles into one hit`() = runTest {
            val server = FakeServer(
                tilesets = listOf(
                    divisions(
                        listOf(
                            FakeTile(tileUrl("a"), listOf(record(division("d1", "Springfield")))),
                            FakeTile(tileUrl("b"), listOf(record(division("d1", "Springfield")))),
                        ),
                    ),
                ),
            )
            val result = client(server).searchPlaces(region, dataset = "overturemaps.org", collection = "division")
            assertEquals(1, result.places.size)
            assertEquals("urn:atgeo:overturemaps.org:division:d1", result.places[0].origin)
        }

        @TestFactory
        fun `keeps the lowest-URL copy across an antimeridian split`(): List<DynamicTest> = listOf(
            listOf("lower", "a", "z", "East copy"),
            listOf("higher", "z", "a", "West copy"),
        ).map { (order, eastName, westName, winner) ->
            dynamicTest("keeps the lowest-URL copy across an antimeridian split (east tile URL sorts $order)") {
                runTest {
                    val server = FakeServer(
                        tilesets = listOf(
                            overture(
                                listOf(
                                    FakeTile(tileUrl(eastName), listOf(record(place("x", "East copy", listOf(point(15.0, 175.0)))))),
                                    FakeTile(tileUrl(westName), listOf(record(place("x", "West copy", listOf(point(15.0, -175.0)))))),
                                ),
                            ),
                        ),
                        tilesFor = { bbox -> TilesAnswer.Urls(listOf(if (bbox.startsWith("170")) tileUrl(eastName) else tileUrl(westName))) },
                    )
                    assertEquals(listOf(winner), client(server).searchPlaces(Region.Bbox(170.0, 10.0, -170.0, 20.0)).places.map(::nameOf))
                }
            }
        }

        @Test
        fun `keeps two records that have neither origin nor uri`() = runTest {
            fun anonymous(name: String) = place("x", name, listOf(point(37.775, -122.415)), noOrigin)
            val server = FakeServer(tilesets = listOf(overture(listOf(FakeTile(tileUrl("a"), listOf(record(anonymous("One")), record(anonymous("Two"))))))))
            val result = client(server).searchPlaces(region, dataset = "overturemaps.org")
            assertEquals(listOf("One", "Two"), result.places.map { nameOf(it)!! }.sorted())
            assertTrue(result.places.all { it.origin == null })
        }

        @Test
        fun `identifies an origin-less record by its uri`() = runTest {
            val value = place("x", "By uri", listOf(point(37.775, -122.415)), noOrigin)
            val server = FakeServer(tilesets = listOf(overture(listOf(FakeTile(tileUrl("a"), listOf(record(value, uri = "https://example.org/r/1")))))))
            assertEquals(listOf("https://example.org/r/1"), client(server).searchPlaces(region, dataset = "overturemaps.org").places.map { it.origin })
        }
    }

    @Nested
    @DisplayName("ordering ties")
    inner class OrderingTies {
        @Test
        fun `orders records equal on score, importance and distance by origin, null last`() = runTest {
            fun same(id: String, extra: Map<String, JsonElement?> = emptyMap()) = record(place(id, "Same", listOf(point(37.775, -122.415)), extra), 5)
            val server = FakeServer(tilesets = listOf(overture(listOf(FakeTile(tileUrl("a"), listOf(same("x", noOrigin), same("c"), same("a"), same("b")))))))
            val result = client(server).searchPlaces(Region.Bbox(-122.42, 37.77, -122.41, 37.78), dataset = "overturemaps.org")
            assertEquals(listOf(placeOrigin("a"), placeOrigin("b"), placeOrigin("c"), null), result.places.map { it.origin })
        }
    }

    @Nested
    @DisplayName("region filtering: bbox")
    inner class RegionFilteringBbox {
        private val region = Region.Bbox(-122.4194, 37.7749, -122.415, 37.7799)

        @Test
        fun `drops a point inside the snapped box but outside the region`() = runTest {
            val kept = search(
                listOf(
                    place("in", "In", listOf(point(37.777, -122.417))),
                    place("south", "South", listOf(point(37.771, -122.417))),
                    place("east", "East", listOf(point(37.777, -122.411))),
                    place("far", "Far", listOf(point(40.0, -100.0))),
                ),
                region,
            )
            assertEquals(listOf("in"), kept)
        }

        @Test
        fun `keeps a point on the region boundary`() = runTest {
            assertEquals(listOf("edge"), search(listOf(place("edge", "Edge", listOf(point(37.7749, -122.4194)))), region))
        }

        @Test
        fun `passes bbox-only records by intersection`() = runTest {
            val kept = search(
                listOf(
                    place("overlap", "Overlap", listOf(bboxLocation(37.776, 37.7, -122.416, -122.5))),
                    place("encloses", "Encloses", listOf(bboxLocation(38.0, 37.0, -122.0, -123.0))),
                    place("inside", "Inside", listOf(bboxLocation(37.777, 37.776, -122.417, -122.418))),
                    place("north", "North", listOf(bboxLocation(38.0, 37.9, -122.416, -122.418))),
                    place("west", "West", listOf(bboxLocation(37.777, 37.776, -122.5, -122.6))),
                ),
                region,
            )
            assertEquals(listOf("encloses", "inside", "overlap"), kept)
        }

        @Test
        fun `tests a record with both point and bbox on the point`() = runTest {
            val kept = search(
                listOf(
                    place("point-out", "Point out", listOf(point(40.0, -100.0), bboxLocation(38.0, 37.0, -122.0, -123.0))),
                    place("point-in", "Point in", listOf(bboxLocation(10.0, 9.0, 10.0, 9.0), point(37.777, -122.417))),
                ),
                region,
            )
            assertEquals(listOf("point-in"), kept)
        }

        @Test
        fun `drops records with no parseable location`() = runTest {
            val garbage = JsonObject(point(0.0, 0.0) + ("latitude" to JsonPrimitive("north-ish")))
            val address = buildJsonObject {
                put("\$type", "community.lexicon.location.address")
                put("country", "US")
                put("street", "1 Main St")
            }
            val missing = buildJsonObject {
                put("\$type", "org.atgeo.place")
                put("origin", placeOrigin("missing"))
                put("name", "Missing")
            }
            val kept = search(
                listOf(
                    place("ok", "Ok", listOf(point(37.777, -122.417))),
                    place("none", "None", emptyList()),
                    place("address", "Address", listOf(address)),
                    place("garbage", "Garbage", listOf(garbage)),
                    missing,
                ),
                region,
            )
            assertEquals(listOf("ok"), kept)
        }

        @Test
        fun `handles a crossing region against points and crossing bboxes`() = runTest {
            val kept = search(
                listOf(
                    place("east-side", "E", listOf(point(0.0, 175.0))),
                    place("west-side", "W", listOf(point(0.0, -175.0))),
                    place("prime", "P", listOf(point(0.0, 0.0))),
                    place("crossing-box", "X", listOf(bboxLocation(1.0, -1.0, -175.0, 178.0))),
                    place("far-box", "F", listOf(bboxLocation(1.0, -1.0, 100.0, 90.0))),
                ),
                Region.Bbox(170.0, -10.0, -170.0, 10.0),
            )
            assertEquals(listOf("crossing-box", "east-side", "west-side"), kept)
        }
    }

    @Nested
    @DisplayName("region filtering: circle")
    inner class RegionFilteringCircle {
        @Test
        fun `uses the true radius, not the enclosing square`() = runTest {
            val lat = 37.7749
            val lon = -122.4194
            val kept = search(
                listOf(
                    place("near", "Near", listOf(point(lat + 0.003, lon))),
                    place("corner", "Corner", listOf(point(lat + 0.004, lon + 0.005))),
                    place("beyond", "Beyond", listOf(point(lat + 0.005, lon))),
                ),
                Region.Circle(lat, lon, 500.0),
            )
            assertEquals(listOf("near"), kept)
        }

        @Test
        fun `passes bbox-only records by nearest-point distance`() = runTest {
            val kept = search(
                listOf(
                    place("close-box", "Close", listOf(bboxLocation(0.4, 0.3, 0.4, 0.3))),
                    place("corner-box", "Corner", listOf(bboxLocation(0.5, 0.4, 0.5, 0.4))),
                    place("surrounding", "Surrounding", listOf(bboxLocation(1.0, -1.0, 1.0, -1.0))),
                ),
                Region.Circle(0.0, 0.0, 55597.0),
            )
            assertEquals(listOf("close-box", "surrounding"), kept)
        }

        @Test
        fun `keeps a high-latitude bbox-only record that clips the circle, with distance within the radius`() = runTest {
            val lat = 85.0
            val radius = 370000.0
            // Measured to the box edge along the center's parallel: 379.9 km. The true nearest point is 357.1 km.
            val box = place("clipping", "Clipping", listOf(bboxLocation(89.0, 80.0, 50.0, 40.0)))
            assertTrue(haversine(lat, 0.0, lat, 40.0) > radius)
            val server = FakeServer(tilesets = listOf(overture(listOf(FakeTile(tileUrl("t"), listOf(record(box)))))))
            val places = client(server).searchPlaces(Region.Circle(lat, 0.0, radius)).places
            assertEquals(listOf("clipping"), places.map { idOf(it.origin) })
            assertTrue(places[0].distance <= radius)
        }

        @Test
        fun `finds the nearest longitude around the antimeridian`() = runTest {
            val kept = search(
                listOf(
                    place("near-edge", "Near", listOf(bboxLocation(1.0, -1.0, 180.0, 170.0))),
                    place("far-edge", "Far", listOf(bboxLocation(1.0, -1.0, 170.0, 160.0))),
                    place("crossing", "Crossing", listOf(bboxLocation(1.0, -1.0, -170.0, 170.0))),
                ),
                Region.Circle(0.0, -175.0, 667170.0),
            )
            assertEquals(listOf("crossing", "near-edge"), kept)
        }

        @Test
        fun `measures point distance across the antimeridian`() = runTest {
            val kept = search(
                listOf(
                    place("p-wrap", "Wrap", listOf(point(0.0, 179.5))),
                    place("p-far", "Far", listOf(point(0.0, 178.0))),
                    place("p-same", "Same", listOf(point(0.0, -176.0))),
                ),
                Region.Circle(0.0, -175.0, 667170.0),
            )
            assertEquals(listOf("p-same", "p-wrap"), kept)
        }
    }

    @Nested
    @DisplayName("determinism")
    inner class Determinism {
        private fun permutations(xs: List<String>): List<List<String>> =
            if (xs.size <= 1) listOf(xs) else xs.flatMap { x -> permutations(xs - x).map { listOf(x) + it } }

        private val tiles = listOf(
            FakeTile(
                tileUrl("a"),
                listOf(
                    record(place("dup", "Copy A", listOf(point(37.775, -122.415))), 5),
                    record(place("a1", "Alpha", listOf(point(37.772, -122.412))), 5),
                    record(place("a2", "Alpha", listOf(point(37.772, -122.412))), 5),
                ),
            ),
            FakeTile(
                tileUrl("b"),
                listOf(record(place("dup", "Copy B", listOf(point(37.775, -122.415))), 5), record(place("b1", "Alpha", listOf(point(37.772, -122.412))), 5)),
            ),
            FakeTile(
                tileUrl("c"),
                listOf(record(place("dup", "Copy C", listOf(point(37.775, -122.415))), 5), record(place("c1", "Alpha", listOf(point(37.772, -122.412))), 5)),
            ),
        )
        private val region = Region.Bbox(-122.42, 37.77, -122.41, 37.78)

        // Staggered virtual-time delays make the tiles arrive in the given order.
        private suspend fun TestScope.run(arrival: List<String>, text: String?): SearchResult {
            val server = FakeServer(tilesets = listOf(overture(tiles)))
            val delayed = HttpTransport { url ->
                val rank = arrival.indexOfFirst { url == tileUrl(it) }
                if (rank >= 0) delay(rank * 15L)
                server.get(url)
            }
            return client(delayed).searchPlaces(region, text = text)
        }

        @TestFactory
        fun `gives identical output for every tile arrival order`(): List<DynamicTest> = listOf(null, "Alpha").map { text ->
            dynamicTest("gives identical output for every tile arrival order (text $text)") {
                runTest {
                    val outputs = permutations(listOf("a", "b", "c")).map { run(it, text) }
                    assertEquals(1, outputs.toSet().size)
                    val places = outputs[0].places
                    assertEquals(listOf("a1", "a2", "b1", "c1", "dup"), places.map { idOf(it.origin) }.sorted())
                    assertEquals("Copy A", places.first { idOf(it.origin) == "dup" }.place["name"].stringOrNull())
                }
            }
        }
    }
}
