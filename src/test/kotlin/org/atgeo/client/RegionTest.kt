package org.atgeo.client

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val COORD = Regex("""^-?\d{1,3}\.\d{2}$""")

private fun bbox(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double) = Region.Bbox(minLon, minLat, maxLon, maxLat)

private suspend fun TestScope.emitted(region: Region): List<String> {
    val server = FakeServer()
    client(server).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
    return server.requests.filter { "org.atgeo.getTiles" in it }.map { paramsOf(it)["bbox"].orEmpty() }
}

private fun parse(bbox: String) = bbox.split(",").map { it.toDouble() }

// vitest's toBeCloseTo(expected, digits): |actual - expected| < 10^-digits / 2.
private fun assertCloseTo(expected: Double, actual: Double, digits: Int) =
    assertTrue(abs(actual - expected) < Math.pow(10.0, -digits.toDouble()) / 2, "expected $expected, got $actual")

class RegionTest {
    // Contract §3 and the port spec: the box's fields are named for the compass sides, in the order of the wire string.
    @Nested
    @DisplayName("bbox field names")
    inner class BboxNames {
        @Test
        fun `a bbox names its sides west, south, east and north, in wire order`() {
            val box = Region.Bbox(west = 1.0, south = 2.0, east = 3.0, north = 4.0)
            assertEquals(Region.Bbox(1.0, 2.0, 3.0, 4.0), box)
            assertEquals(listOf(1.0, 2.0, 3.0, 4.0), listOf(box.west, box.south, box.east, box.north))
            val (west, south, east, north) = box
            assertEquals(listOf(1.0, 2.0, 3.0, 4.0), listOf(west, south, east, north))
        }

        @Test
        fun `sends west, south, east, north in that order`() = runTest {
            val server = FakeServer()
            client(server).searchPlaces(Region.Bbox(west = -122.42, south = 37.77, east = -122.41, north = 37.78), OVERTURE_DATASET)
            assertEquals("-122.42,37.77,-122.41,37.78", paramsOf(server.requests.single { "getTiles" in it }).getValue("bbox"))
        }

        @Test
        fun `reads west greater than east as crossing the antimeridian`() = runTest {
            val server = FakeServer()
            client(server).searchPlaces(Region.Bbox(west = 170.0, south = 10.0, east = -170.0, north = 20.0), OVERTURE_DATASET)
            assertEquals(
                listOf("170.00,10.00,180.00,20.00", "-180.00,10.00,-170.00,20.00"),
                server.requests.filter { "getTiles" in it }.map { paramsOf(it).getValue("bbox") },
            )
        }
    }

    @Nested
    @DisplayName("coordinate serialization")
    inner class CoordinateSerialization {
        private val adversarial = listOf(
            "full GPS precision circle" to Region.Circle(37.7749295, -122.4194155, 250.123456),
            "full GPS precision bbox" to bbox(-122.4194155, 37.7749295, -122.4094155, 37.7849295),
            "trailing zeros" to bbox(-122.4, 37.7, -122.3, 37.8),
            "scientific notation tiny" to bbox(1e-7, 1e-7, 2e-7, 2e-7),
            "scientific notation" to bbox(3.7749e1, 3.7749e1, 3.775e1, 3.776e1),
            "negative scientific notation" to bbox(-1e-7, -2e-7, 1e-7, 2e-7),
            "tiny radius" to Region.Circle(12.3456789, 98.7654321, 1e-9),
            "radius zero" to Region.Circle(12.3456789, 98.7654321, 0.0),
            "zero-area bbox" to bbox(10.123456, 20.123456, 10.123456, 20.123456),
            "negative zero bbox" to bbox(-0.0, -0.0, 0.0, 0.0),
            "negative zero circle" to Region.Circle(-0.0, -0.0, 0.0),
            "tiny negative" to bbox(-0.0001, -0.0001, -0.00001, -0.00001),
            "circle at pole" to Region.Circle(90.0, 0.0, 1000.0),
            "circle at antimeridian" to Region.Circle(0.0, 180.0, 1000.0),
            "crossing bbox" to bbox(179.123456, 1.987654, -179.123456, 2.987654),
        )

        @TestFactory
        fun `emits only two-decimal coordinates`(): List<DynamicTest> = adversarial.map { (name, region) ->
            dynamicTest("emits only two-decimal coordinates: $name") {
                runTest {
                    val boxes = emitted(region)
                    assertTrue(boxes.isNotEmpty())
                    for (b in boxes) {
                        val parts = b.split(",")
                        assertEquals(4, parts.size)
                        for (c in parts) assertTrue(COORD.matches(c), c)
                    }
                }
            }
        }

        @Test
        fun `never emits a coordinate outside the valid range`() = runTest {
            val regions = listOf(
                bbox(-180.0, -90.0, 180.0, 90.0),
                bbox(180.0, 90.0, 180.0, 90.0),
                bbox(-180.0, -90.0, -180.0, -90.0),
                Region.Circle(90.0, 180.0, 5.0),
                Region.Circle(-90.0, -180.0, 5.0),
                Region.Circle(89.999, 179.999, 100000.0),
            )
            for (region in regions) {
                for (b in emitted(region)) {
                    val (minLon, minLat, maxLon, maxLat) = parse(b)
                    assertTrue(minLon >= -180 && maxLon <= 180 && minLat >= -90 && maxLat <= 90, b)
                    assertTrue(minLon < maxLon && minLat < maxLat, b)
                }
            }
        }
    }

    @Nested
    @DisplayName("outward snapping")
    inner class OutwardSnapping {
        @Test
        fun `floors mins and ceils maxes`() = runTest {
            assertEquals(listOf("-122.42,37.77,-122.40,37.79"), emitted(bbox(-122.4194155, 37.7749295, -122.4094155, 37.7849295)))
        }

        @Test
        fun `contains the caller's region for arbitrary precision input`() = runTest {
            val inputs = listOf(
                listOf(-122.419415, 37.774929, -122.418001, 37.775001),
                listOf(0.001, 0.002, 0.003, 0.004),
                listOf(-0.004, -0.003, -0.002, -0.001),
                listOf(151.2093, -33.8688, 151.2193, -33.8588),
                listOf(-73.9857, 40.7484, -73.9757, 40.7584),
            )
            for ((minLon, minLat, maxLon, maxLat) in inputs) {
                val (a, b, c, d) = parse(emitted(bbox(minLon, minLat, maxLon, maxLat))[0])
                assertTrue(a <= minLon && b <= minLat && c >= maxLon && d >= maxLat)
                assertTrue(c - a < maxLon - minLon + 0.0201)
                assertTrue(d - b < maxLat - minLat + 0.0201)
            }
        }

        @Test
        fun `does not widen a grid-aligned bbox through float error`() = runTest {
            assertEquals(listOf("-122.42,37.77,-122.41,37.78"), emitted(bbox(-122.42, 37.77, -122.41, 37.78)))
        }

        @Test
        fun `does not widen grid-aligned values anywhere on the grid`() = runTest {
            for (k in 0 until 9000 step 97) {
                val lat = listOf((k + 1) / 100.0, (k + 6) / 100.0)
                val lon = listOf(-(k + 8) / 100.0, -(k + 5) / 100.0)
                val expected = listOf(lon[0], lat[0], lon[1], lat[1]).joinToString(",") { String.format(java.util.Locale.ROOT, "%.2f", it) }
                assertEquals(listOf(expected), emitted(bbox(lon[0], lat[0], lon[1], lat[1])))
            }
        }
    }

    @Nested
    @DisplayName("degenerate spans")
    inner class DegenerateSpans {
        @Test
        fun `widens a grid-aligned point by one step`() = runTest {
            assertEquals(listOf("10.00,20.00,10.01,20.01"), emitted(bbox(10.0, 20.0, 10.0, 20.0)))
        }

        @Test
        fun `widens a zero radius circle on the grid`() = runTest {
            assertEquals(listOf("10.00,20.00,10.01,20.01"), emitted(Region.Circle(20.0, 10.0, 0.0)))
        }

        @Test
        fun `widens negative zero`() = runTest {
            assertEquals(listOf("0.00,0.00,0.01,0.01"), emitted(bbox(-0.0, -0.0, 0.0, 0.0)))
        }

        @Test
        fun `widens a line`() = runTest {
            assertEquals(listOf("10.00,20.00,10.50,20.01"), emitted(bbox(10.0, 20.0, 10.5, 20.0)))
        }

        @Test
        fun `steps min down at latitude 90 and longitude 180`() = runTest {
            assertEquals(listOf("0.00,89.99,0.01,90.00"), emitted(bbox(0.0, 90.0, 0.0, 90.0)))
            assertEquals(listOf("179.99,0.00,180.00,0.01"), emitted(bbox(180.0, 0.0, 180.0, 0.0)))
        }

        @Test
        fun `does not widen a span that snapping already opened`() = runTest {
            assertEquals(listOf("10.00,20.00,10.01,20.01"), emitted(bbox(10.005, 20.005, 10.005, 20.005)))
        }
    }

    @Nested
    @DisplayName("circle to bbox")
    inner class CircleToBbox {
        @Test
        @DisplayName("uses 111194.927 m per degree of latitude")
        fun usesMetresPerDegree() = runTest {
            assertEquals(listOf("-1.00,-1.00,1.00,1.00"), emitted(Region.Circle(0.0, 0.0, 111194.927)))
        }

        @Test
        fun `widens longitude to the spherical cap's half-width`() = runTest {
            val (minLon, minLat, maxLon, maxLat) = parse(emitted(Region.Circle(60.0, 10.0, 111194.927))[0])
            assertCloseTo(59.0, minLat, 1)
            assertCloseTo(61.0, maxLat, 1)
            assertTrue(minLon <= 8 && minLon >= 7.99)
            assertTrue(maxLon >= 12 && maxLon <= 12.01)
        }

        // asin(sin 20° / cos 60°) = 43.16°; the flat 20° / cos 60° is 40°.
        @Test
        fun `reaches the cap's true east and west edges at high latitude`() = runTest {
            assertEquals(listOf("-43.17,40.00,43.17,80.00"), emitted(Region.Circle(60.0, 0.0, 20 * 111194.927)))
        }

        @Test
        fun `covers all longitudes for a polar circle`() = runTest {
            val north = emitted(Region.Circle(89.9, 12.34, 50000.0))
            assertEquals(1, north.size)
            val (nMinLon, nMinLat, nMaxLon, nMaxLat) = parse(north[0])
            assertEquals(listOf(-180.0, 180.0, 90.0), listOf(nMinLon, nMaxLon, nMaxLat))
            assertTrue(nMinLat <= 89.46 && nMinLat >= 89.44)

            val south = emitted(Region.Circle(-89.9, -45.0, 50000.0))
            assertEquals(1, south.size)
            val (sMinLon, sMinLat, sMaxLon, sMaxLat) = parse(south[0])
            assertEquals(listOf(-180.0, 180.0, -90.0), listOf(sMinLon, sMaxLon, sMinLat))
            assertTrue(sMaxLat <= -89.44 && sMaxLat >= -89.46)
        }
    }

    @Nested
    @DisplayName("antimeridian")
    inner class Antimeridian {
        @Test
        fun `splits a crossing bbox into two getTiles calls`() = runTest {
            assertEquals(listOf("170.00,10.00,180.00,20.00", "-180.00,10.00,-170.00,20.00"), emitted(bbox(170.0, 10.0, -170.0, 20.0)))
        }

        @Test
        fun `splits a circle that wraps past 180`() = runTest {
            assertEquals(listOf("178.50,-1.00,180.00,1.00", "-180.00,-1.00,-179.50,1.00"), emitted(Region.Circle(0.0, 179.5, 111194.927)))
        }

        @Test
        fun `splits a circle that wraps past -180`() = runTest {
            assertEquals(listOf("179.50,-1.00,180.00,1.00", "-180.00,-1.00,-178.50,1.00"), emitted(Region.Circle(0.0, -179.5, 111194.927)))
        }

        @Test
        fun `issues a single call for a non-crossing bbox`() = runTest {
            assertEquals(1, emitted(bbox(-10.0, 10.0, 10.0, 20.0)).size)
        }

        @Test
        fun `snaps each half independently`() = runTest {
            assertEquals(listOf("170.12,10.12,180.00,20.13", "-180.00,10.12,-170.12,20.13"), emitted(bbox(170.123, 10.123, -170.123, 20.123)))
        }

        @Test
        fun `merges results from both halves`() = runTest {
            val west = "https://tiles.example/west.json"
            val east = "https://tiles.example/east.json"
            val server = FakeServer(
                tilesets = listOf(
                    overture(
                        listOf(
                            FakeTile(west, listOf(record(place("w", "West", listOf(point(15.0, 175.0)))))),
                            FakeTile(east, listOf(record(place("e", "East", listOf(point(15.0, -175.0)))))),
                        ),
                    ),
                ),
                tilesFor = { b -> TilesAnswer.Urls(listOf(if (b.startsWith("170")) west else east)) },
            )
            val result = client(server).searchPlaces(bbox(170.0, 10.0, -170.0, 20.0), OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertEquals(listOf(placeOrigin("e"), placeOrigin("w")), result.places.map { it.origin!! }.sorted())
            assertEquals(true, result.complete)
        }
    }

    @Nested
    @DisplayName("invalid regions")
    inner class InvalidRegions {
        private val bad = listOf(
            "NaN lat" to Region.Circle(Double.NaN, 0.0, 10.0),
            "NaN lon" to Region.Circle(0.0, Double.NaN, 10.0),
            "Infinity lon" to Region.Circle(0.0, Double.POSITIVE_INFINITY, 10.0),
            "NaN radius" to Region.Circle(0.0, 0.0, Double.NaN),
            "Infinity radius" to Region.Circle(0.0, 0.0, Double.POSITIVE_INFINITY),
            "negative radius" to Region.Circle(0.0, 0.0, -1.0),
            "lat above 90" to Region.Circle(90.01, 0.0, 10.0),
            "lat below -90" to Region.Circle(-91.0, 0.0, 10.0),
            "lon above 180" to Region.Circle(0.0, 180.5, 10.0),
            "lon below -180" to Region.Circle(0.0, -181.0, 10.0),
            "bbox NaN" to bbox(0.0, 0.0, Double.NaN, 1.0),
            "bbox Infinity" to bbox(0.0, 0.0, 1.0, Double.POSITIVE_INFINITY),
            "bbox -Infinity" to bbox(Double.NEGATIVE_INFINITY, 0.0, 1.0, 1.0),
            "bbox minLat > maxLat" to bbox(0.0, 10.0, 1.0, 5.0),
            "bbox lat above 90" to bbox(0.0, 0.0, 1.0, 91.0),
            "bbox lat below -90" to bbox(0.0, -91.0, 1.0, 0.0),
            "bbox lon above 180" to bbox(0.0, 0.0, 181.0, 1.0),
            "bbox lon below -180" to bbox(-181.0, 0.0, 1.0, 1.0),
        )

        @TestFactory
        fun `throws IllegalArgumentException before any request`(): List<DynamicTest> = bad.map { (name, region) ->
            dynamicTest("throws IllegalArgumentException before any request: $name") {
                runTest {
                    val server = FakeServer()
                    assertFailsWith<IllegalArgumentException> { client(server).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION) }
                    assertEquals(0, server.requests.size)
                }
            }
        }

        @Test
        fun `accepts minLon greater than maxLon`() = runTest {
            client(FakeServer()).searchPlaces(bbox(170.0, 0.0, -170.0, 1.0), OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
        }

        @Test
        fun `throws IllegalArgumentException before any request for similarity sort without text`() = runTest {
            val server = FakeServer()
            assertFailsWith<IllegalArgumentException> { client(server).searchPlaces(bbox(0.0, 0.0, 1.0, 1.0), OVERTURE_DATASET, sort = SortMode.SIMILARITY, collection = OVERTURE_COLLECTION) }
            assertEquals(0, server.requests.size)
        }

        // Contract §3: the region is checked first, so a call with both faults reports the region.
        @Test
        fun `checks the region before the sort (no reference counterpart)`() = runTest {
            val error = assertFailsWith<IllegalArgumentException> {
                client(FakeServer()).searchPlaces(Region.Circle(Double.NaN, 0.0, 1.0), OVERTURE_DATASET, sort = SortMode.SIMILARITY, collection = OVERTURE_COLLECTION)
            }
            assertEquals("invalid circle", error.message)
        }
    }

    // Contract §3, computed with the reference implementation.
    @Nested
    @DisplayName("contract worked examples (no reference counterpart)")
    inner class WorkedExamples {
        @TestFactory
        fun `bbox strings`(): List<DynamicTest> = listOf(
            Region.Circle(37.7598, -122.4194, 1000.0) to listOf("-122.44,37.75,-122.40,37.77"),
            Region.Circle(10.0, 20.0, 0.0) to listOf("20.00,10.00,20.01,10.01"),
            Region.Circle(0.0, 179.999, 5000.0) to listOf("179.95,-0.05,180.00,0.05", "-180.00,-0.05,-179.95,0.05"),
            Region.Circle(0.0, -179.999, 5000.0) to listOf("179.95,-0.05,180.00,0.05", "-180.00,-0.05,-179.95,0.05"),
            Region.Circle(89.9, 0.0, 50000.0) to listOf("-180.00,89.45,180.00,90.00"),
            Region.Circle(80.0, 0.0, 100000.0) to listOf("-5.19,79.10,5.19,80.90"),
            Region.Circle(-0.0, -0.0, 0.0) to listOf("0.00,0.00,0.01,0.01"),
            Region.Bbox(-122.42, 37.77, -122.41, 37.78) to listOf("-122.42,37.77,-122.41,37.78"),
            Region.Bbox(-122.4194155, 37.7749295, -122.4094155, 37.7849295) to listOf("-122.42,37.77,-122.40,37.79"),
            Region.Bbox(170.0, 10.0, -170.0, 20.0) to listOf("170.00,10.00,180.00,20.00", "-180.00,10.00,-170.00,20.00"),
            Region.Bbox(180.0, 90.0, 180.0, 90.0) to listOf("179.99,89.99,180.00,90.00"),
            Region.Bbox(10.0, 10.0, 10.5, 10.0) to listOf("10.00,10.00,10.50,10.01"),
        ).map { (region, expected) -> dynamicTest("$region") { assertEquals(expected, queryBboxes(region)) } }

        @TestFactory
        fun centre(): List<DynamicTest> = listOf(
            Region.Bbox(170.0, 10.0, -170.0, 20.0) to LatLon(15.0, 180.0),
            Region.Bbox(100.0, 0.0, -100.0, 0.0) to LatLon(0.0, 180.0),
            Region.Circle(1.5, -2.5, 10.0) to LatLon(1.5, -2.5),
        ).map { (region, expected) -> dynamicTest("$region") { assertEquals(expected, center(region)) } }
    }
}
