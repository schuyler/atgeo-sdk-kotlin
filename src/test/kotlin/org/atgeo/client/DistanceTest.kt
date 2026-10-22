package org.atgeo.client

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun boxAt(south: Double, north: Double, west: Double, east: Double) =
    locate(place("b", "Box", listOf(bboxLocation(north, south, east, west))))

private fun pointAt(lat: Double, lon: Double) = locate(place("p", "Point", listOf(point(lat, lon))))

// Minimum haversine distance from the center to a dense sampling of the box boundary.
private fun sampledDistance(lat: Double, lon: Double, south: Double, north: Double, west: Double, east: Double): Double {
    val steps = 100000
    val spans = if (west <= east) listOf(west to east) else listOf(west to 180.0, -180.0 to east)
    var best = Double.POSITIVE_INFINITY
    for ((lo, hi) in spans) {
        for (i in 0..steps) {
            val la = south + ((north - south) * i) / steps
            val lo2 = lo + ((hi - lo) * i) / steps
            for ((a, b) in listOf(la to lo, la to hi, south to lo2, north to lo2)) {
                best = min(best, haversine(lat, lon, a, b))
            }
        }
    }
    return best
}

// vitest's toBeCloseTo(expected, digits): |actual - expected| < 10^-digits / 2.
private fun assertCloseTo(expected: Double, actual: Double, digits: Int) {
    assertTrue(abs(actual - expected) < 10.0.pow(-digits) / 2, "expected $expected, got $actual")
}

private fun assertRelative(expected: Double, actual: Double) {
    assertTrue(abs(actual - expected) <= 1e-6 * abs(expected), "expected $expected, got $actual")
}

private fun region(west: Double, south: Double, east: Double, north: Double) = Region.Bbox(west, south, east, north)

class DistanceTest {
    @Nested
    @DisplayName("distanceTo: containment is exactly zero")
    inner class Containment {
        @Test
        fun `for a latitude that does not survive a radian round trip`() {
            assertEquals(0.0, distanceTo(boxAt(46.0, 47.0, 10.0, 11.0), 46.0986, 10.5))
        }

        @Test
        fun `for center lon 180 in a box with west -180`() {
            assertEquals(0.0, distanceTo(boxAt(40.0, 50.0, -180.0, -170.0), 45.0, 180.0))
        }

        @Test
        fun `for center lon -180 in a box with east 180`() {
            assertEquals(0.0, distanceTo(boxAt(40.0, 50.0, 170.0, 180.0), 45.0, -180.0))
        }

        @Test
        fun `for a pole center in a box reaching the pole whose longitudes exclude 0`() {
            assertEquals(0.0, distanceTo(boxAt(-90.0, -80.0, 10.0, 20.0), -90.0, 0.0))
        }

        @Test
        fun `for a point exactly at the center`() {
            assertEquals(0.0, distanceTo(pointAt(46.0986, 10.5), 46.0986, 10.5))
        }

        @TestFactory
        fun `for a point and a center at its seam twin`(): List<DynamicTest> =
            listOf(180.0 to -180.0, -180.0 to 180.0).map { (pointLon, centerLon) ->
                dynamicTest("for a point at lon $pointLon and a center at its seam twin $centerLon") {
                    assertEquals(0.0, distanceTo(pointAt(10.0, pointLon), 10.0, centerLon))
                }
            }
    }

    @Nested
    @DisplayName("distanceTo: outside a box")
    inner class OutsideABox {
        private val cases = listOf(
            Triple("due south within the longitude range", 0.0 to 5.0, listOf(10.0, 20.0, 0.0, 10.0)),
            Triple("east of a high-latitude box", 65.0 to 10.0, listOf(60.0, 70.0, 20.0, 30.0)),
            Triple("more than 90 degrees of longitude away", 50.0 to 0.0, listOf(40.0, 60.0, 120.0, 140.0)),
            Triple("across the antimeridian from a box", 60.0 to -179.0, listOf(55.0, 65.0, 175.0, 179.0)),
            Triple("from an antimeridian-crossing box", 45.0 to -160.0, listOf(40.0, 50.0, 170.0, -170.0)),
            Triple("west of an antimeridian-crossing box", 45.0 to 150.0, listOf(40.0, 50.0, 170.0, -170.0)),
        )

        @TestFactory
        fun `matches the sampled boundary distance`(): List<DynamicTest> = cases.map { (name, center, box) ->
            val (lat, lon) = center
            val (south, north, west, east) = box
            dynamicTest("matches the sampled boundary distance $name") {
                val expected = sampledDistance(lat, lon, south, north, west, east)
                assertCloseTo(expected, assertNotNull(distanceTo(boxAt(south, north, west, east), lat, lon)), 1)
            }
        }

        @Test
        fun `is strictly less than the latitude-clamped distance for a high-latitude box to the east`() {
            val clamped = haversine(65.0, 10.0, 65.0, 20.0)
            val actual = assertNotNull(distanceTo(boxAt(60.0, 70.0, 20.0, 30.0), 65.0, 10.0))
            assertTrue(actual < clamped - 1000)
        }
    }

    @Nested
    @DisplayName("distanceTo: no location")
    inner class NoLocation {
        @Test
        fun `is null`() {
            assertNull(distanceTo(locate(place("n", "None", emptyList())), 0.0, 0.0))
        }
    }

    @Nested
    @DisplayName("inRegion: bbox region seams")
    inner class Seams {
        @TestFactory
        fun keeps(): List<DynamicTest> = listOf(
            Triple("a point at lon 180 in a region with west -180", pointAt(45.0, 180.0), region(-180.0, 40.0, -170.0, 50.0)),
            Triple("a point at lon -180 in a region with east 180", pointAt(45.0, -180.0), region(170.0, 40.0, 180.0, 50.0)),
            Triple("a box ending at 180 and a region starting at -180", boxAt(40.0, 50.0, 170.0, 180.0), region(-180.0, 40.0, -170.0, 50.0)),
            Triple("a box starting at -180 and a region ending at 180", boxAt(40.0, 50.0, -180.0, -170.0), region(170.0, 40.0, 180.0, 50.0)),
            Triple("a point at the pole whatever its longitude", pointAt(90.0, 0.0), region(10.0, 80.0, 20.0, 90.0)),
            Triple("a box reaching the pole at other longitudes", boxAt(85.0, 90.0, 100.0, 110.0), region(10.0, 80.0, 20.0, 90.0)),
        ).map { (name, locations, r) -> dynamicTest("keeps $name") { assertTrue(inRegion(locations, r)) } }

        @TestFactory
        fun drops(): List<DynamicTest> = listOf(
            Triple("a point just short of the seam", pointAt(45.0, 179.99), region(-180.0, 40.0, -170.0, 50.0)),
            Triple("a box just short of the seam", boxAt(40.0, 50.0, 160.0, 179.99), region(-180.0, 40.0, -170.0, 50.0)),
            Triple("a polar box when the region stops short of the pole", boxAt(85.0, 90.0, 100.0, 110.0), region(10.0, 80.0, 20.0, 89.99)),
        ).map { (name, locations, r) -> dynamicTest("drops $name") { assertFalse(inRegion(locations, r)) } }
    }

    // Contract §6, to 1e-6 relative; containment exact.
    @Nested
    @DisplayName("contract worked examples (no reference counterpart)")
    inner class WorkedExamples {
        @Test
        fun haversine() {
            assertRelative(111195.0802335329, haversine(0.0, 0.0, 1.0, 0.0))
            assertRelative(559121.3493517868, haversine(37.7749, -122.4194, 34.0522, -118.2437))
        }

        // (place, centre lat, centre lon, metres); boxes as north, south, east, west.
        @TestFactory
        fun distances(): List<DynamicTest> {
            fun box(n: Double, s: Double, e: Double, w: Double) = locate(place("b", "Box", listOf(bboxLocation(n, s, e, w))))
            return listOf(
                Triple("point 0.001, 0", pointAt(0.001, 0.0), Triple(0.0, 0.0, 111.19508023353292)),
                Triple("box 46, 44, 11, 9 from 45, 10", box(46.0, 44.0, 11.0, 9.0), Triple(45.0, 10.0, 0.0)),
                Triple("box 46, 44, 11, 9 from 45, 8", box(46.0, 44.0, 11.0, 9.0), Triple(45.0, 8.0, 78624.79923702011)),
                Triple("box 46, 44, 11, 9 from 47, 10", box(46.0, 44.0, 11.0, 9.0), Triple(47.0, 10.0, 111195.0802335329)),
                Triple("box 46, 44, 11, 9 from 47, 8", box(46.0, 44.0, 11.0, 9.0), Triple(47.0, 8.0, 134989.80127268212)),
                Triple("box 80, 70, 30, 10 from 75, 60", box(80.0, 70.0, 30.0, 10.0), Triple(75.0, 60.0, 826787.9307589367)),
                Triple("box 50, 40, -170, -180 from 45, 180", box(50.0, 40.0, -170.0, -180.0), Triple(45.0, 180.0, 0.0)),
                Triple("box 10, -10, -170, 170 from 0, 160", box(10.0, -10.0, -170.0, 170.0), Triple(0.0, 160.0, 1111950.8023353291)),
            ).map { (name, locations, expected) ->
                val (lat, lon, metres) = expected
                dynamicTest(name) {
                    val actual = assertNotNull(distanceTo(locations, lat, lon))
                    if (metres == 0.0) assertEquals(0.0, actual) else assertRelative(metres, actual)
                }
            }
        }

        @Test
        fun `no location is null`() {
            assertNull(distanceTo(Locations(null, null), 12.0, 34.0))
        }

        @Test
        fun `a circle uses the true radius`() {
            val north = pointAt(0.001, 0.0) // 111.195 m
            assertTrue(inRegion(north, Region.Circle(0.0, 0.0, 111.2)))
            assertFalse(inRegion(north, Region.Circle(0.0, 0.0, 111.1)))
        }
    }
}
