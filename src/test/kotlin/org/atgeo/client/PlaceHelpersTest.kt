package org.atgeo.client

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The public readers of a place: stringOrNull, name and location (no reference counterpart). */
class PlaceHelpersTest {
    private fun hit(place: JsonObject) = SearchHit(null, place, null, 0.0, null)

    @Nested
    inner class StringOrNull {
        @Test
        fun `reads a JSON string and nothing else`() {
            val place = JsonObject(
                mapOf(
                    "s" to JsonPrimitive("Joe's"),
                    "n" to JsonPrimitive(5),
                    "b" to JsonPrimitive(true),
                    "z" to JsonNull,
                    "a" to JsonArray(listOf(JsonPrimitive("x"))),
                ),
            )
            assertEquals("Joe's", place["s"].stringOrNull())
            for (key in listOf("n", "b", "z", "a", "missing")) assertNull(place[key].stringOrNull(), key)
        }
    }

    @Nested
    inner class Name {
        @Test
        fun `is the place's name when it is a string`() {
            assertEquals("Red Arrow Diner", hit(place("a", "Red Arrow Diner", emptyList())).name)
        }

        @Test
        fun `is null when the name is missing or not a string`() {
            assertNull(hit(place("a", "x", emptyList(), mapOf("name" to null))).name)
            assertNull(hit(place("a", "x", emptyList(), mapOf("name" to JsonPrimitive(7)))).name)
        }
    }

    @Nested
    inner class Location {
        @Test
        fun `parses the first usable point and the first usable box`() {
            val unusable = JsonObject(mapOf("\$type" to JsonPrimitive("community.lexicon.location.geo"), "latitude" to JsonPrimitive("north")))
            val location = hit(
                place(
                    "a", "A",
                    listOf(unusable, point(42.99, -71.46), point(1.0, 2.0), bboxLocation(north = 43.0, south = 42.9, east = -71.4, west = -71.5)),
                ),
            ).location
            assertEquals(PlaceLocation.Point(42.99, -71.46), location.point)
            assertEquals(PlaceLocation.Box(south = 42.9, west = -71.5, north = 43.0, east = -71.4), location.bbox)
        }

        @Test
        fun `keeps west greater than east for a box across the antimeridian`() {
            val box = hit(place("a", "A", listOf(bboxLocation(north = 20.0, south = 10.0, east = -170.0, west = 170.0)))).location.bbox
            assertEquals(PlaceLocation.Box(south = 10.0, west = 170.0, north = 20.0, east = -170.0), box)
        }

        @Test
        fun `reads a coordinate given as a JSON number`() {
            val geo = JsonObject(
                mapOf(
                    "\$type" to JsonPrimitive("community.lexicon.location.geo"),
                    "latitude" to JsonPrimitive(1.5),
                    "longitude" to JsonPrimitive("-2.25"),
                ),
            )
            assertEquals(PlaceLocation.Point(1.5, -2.25), hit(place("a", "A", listOf(geo))).location.point)
        }

        @Test
        fun `has neither without usable locations`() {
            val location = hit(place("a", "A", listOf(JsonNull, JsonPrimitive("x")))).location
            assertNull(location.point)
            assertNull(location.bbox)
        }

        @Test
        fun `is the location a search measured distance from`() = runTest {
            val server = FakeServer(tilesets = listOf(overture(listOf(FakeTile(tileUrl("a"), listOf(record(place("p", "P", listOf(point(43.0, -71.45))))))))))
            val found = client(server).searchPlaces(Region.Circle(lat = 43.0, lon = -71.45, radius = 100.0)).places.single()
            assertEquals(0.0, found.distance)
            assertEquals(PlaceLocation.Point(43.0, -71.45), found.location.point)
            assertEquals("P", found.name)
            val record = client(server).getPlace(placeOrigin("p"))
            assertEquals(found.location, record.location)
            assertEquals("P", record.name)
        }
    }
}
