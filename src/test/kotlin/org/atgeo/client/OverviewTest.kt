package org.atgeo.client

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private const val EAST = "https://tiles.example/east.json"
private const val WEST = "https://tiles.example/west.json"
private val region = Region.Bbox(-122.42, 37.77, -122.41, 37.78)
private val tiles = listOf(
    FakeTile(EAST, listOf(record(place("e", "East", listOf(point(37.773, -122.413)))))),
    FakeTile(WEST, listOf(record(place("w", "West", listOf(point(37.772, -122.412)))))),
)

private fun server(tilesFor: ((String) -> TilesAnswer)? = null, failTiles: Set<String> = emptySet()) =
    FakeServer(failTiles = failTiles, tilesFor = tilesFor, tilesets = listOf(overture(tiles)))

private fun tilesCount(requests: List<String>) = requests.count { "org.atgeo.getTiles" in it }

class OverviewTest {
    @Nested
    @DisplayName("overview answers")
    inner class OverviewAnswers {
        @Test
        fun `flags an overview answer whose tiles all load as overview and complete`() = runTest {
            val result = client(server({ TilesAnswer.Listing(listOf(EAST, WEST), overview = true) })).searchPlaces(region)
            assertEquals(true, result.overview)
            assertEquals(true, result.complete)
            // The reference also asserts no "summary" property; SearchResult has a fixed shape.
        }

        @Test
        fun `does not flag a regular answer with a failed tile`() = runTest {
            val result = client(server(failTiles = setOf(WEST))).searchPlaces(region)
            assertEquals(false, result.overview)
            assertEquals(false, result.complete)
        }

        @Test
        fun `reads an answer without the key as regular`() = runTest {
            assertEquals(false, client(server({ TilesAnswer.Listing(listOf(EAST, WEST)) })).searchPlaces(region).overview)
        }

        @Test
        fun `keeps the flag when the tile-list cache answers`() = runTest {
            val fake = server({ TilesAnswer.Listing(listOf(EAST, WEST), overview = true) })
            val client = client(fake)
            client.searchPlaces(region)
            val second = client.searchPlaces(region)
            assertEquals(1, tilesCount(fake.requests))
            assertEquals(true, second.overview)
        }

        @Test
        fun `flags a split antimeridian search when only one half is overview`() = runTest {
            val fake = server({ bbox ->
                if (bbox.startsWith("170")) TilesAnswer.Listing(listOf(EAST), overview = true) else TilesAnswer.Listing(listOf(WEST))
            })
            assertEquals(true, client(fake).searchPlaces(Region.Bbox(170.0, 10.0, -170.0, 20.0)).overview)
        }
    }
}
