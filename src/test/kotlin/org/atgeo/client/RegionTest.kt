package org.atgeo.client

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

class RegionTest {
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
