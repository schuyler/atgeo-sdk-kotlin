package org.atgeo.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private val here = point(0.0, 0.0)
private val near = Region.Circle(0.0, 0.0, 10.0)

private fun tileRecord(origin: String?, name: String = "Same", importance: Long? = null, locations: List<JsonObject> = listOf(here)) =
    TileRecord(place("x", name, locations, mapOf("origin" to origin?.let(::JsonPrimitive))), importance = importance)

private fun nameOf(hit: SearchHit) = (hit.place["name"] as JsonPrimitive).content

class RankingTest {
    // Contract §7's scoring examples; exact values from the reference.
    @Nested
    @DisplayName("scoring (no reference counterpart)")
    inner class Scoring {
        private val records = listOf("Starbucks", "Star Bakery", "Stardust Bar", "Home Depot")
            .mapIndexed { i, name -> tileRecord("o$i", name) }

        @Test
        fun `scores the contract's Starbux examples and drops Home Depot`() {
            val hits = rank(records, near, normalize("Starbux"), SortMode.FUSED)
            assertEquals(listOf("Starbucks", "Star Bakery", "Stardust Bar"), hits.map(::nameOf))
            assertEquals(listOf(0.9047619047619048, 0.8740259740259739, 0.8529761904761906), hits.map { it.score })
        }

        @Test
        fun `scores a variant name`() {
            val munchen = TileRecord(
                place("m", "München", listOf(here), mapOf("variants" to JsonArray(listOf(JsonObject(mapOf("name" to JsonPrimitive("Munich"))))))),
            )
            assertEquals(listOf(1.0), rank(listOf(munchen), near, normalize("Munich"), SortMode.FUSED).map { it.score })
        }

        @Test
        fun `has no score without text`() {
            assertEquals(listOf<Double?>(null), rank(listOf(tileRecord("a")), near, "", SortMode.FUSED).map { it.score })
        }

        @Test
        fun `skips variants that are not objects`() {
            val value = place("v", "Zzz", listOf(here), mapOf("variants" to JsonArray(listOf(kotlinx.serialization.json.JsonNull, JsonPrimitive("Munich")))))
            assertEquals(emptyList(), rank(listOf(TileRecord(value)), near, "munich", SortMode.FUSED))
        }
    }

    @Nested
    @DisplayName("comparators (no reference counterpart)")
    inner class Comparators {
        @Test
        fun `orders a null origin after every string`() {
            val hits = rank(listOf(tileRecord(null), tileRecord("b"), tileRecord("a")), near, "", SortMode.FUSED)
            assertEquals(listOf("a", "b", null), hits.map { it.origin })
        }

        @Test
        fun `orders origins by UTF-16 code unit`() {
            val hits = rank(listOf("https://a/9", "https://b", "https://A", "https://a/10").map { tileRecord(it) }, near, "", SortMode.FUSED)
            assertEquals(listOf("https://A", "https://a/10", "https://a/9", "https://b"), hits.map { it.origin })
        }

        @Test
        fun `sorts a null importance as zero, above negatives, without overflow at the extremes`() {
            val records = listOf(
                tileRecord("neg", importance = -1),
                tileRecord("min", importance = Long.MIN_VALUE + 1),
                tileRecord("null"),
                tileRecord("zero", importance = 0),
                tileRecord("max", importance = Long.MAX_VALUE),
            )
            assertEquals(listOf("max", "null", "zero", "neg", "min"), rank(records, near, "", SortMode.FUSED).map { it.origin })
        }

        @Test
        fun `ties scores that differ only past the third decimal`() {
            // Same name, so the same score; importance then decides.
            val records = listOf(tileRecord("a", "Alpha", importance = 1), tileRecord("b", "Alpha", importance = 2))
            assertEquals(listOf("b", "a"), rank(records, near, "alpha", SortMode.FUSED).map { it.origin })
        }

        @Test
        fun `distance mode puts a point before a box around it, then by importance`() {
            val box = bboxLocation(1.0, -1.0, 1.0, -1.0)
            val records = listOf(
                tileRecord("box", locations = listOf(box)),
                tileRecord("low", importance = 1),
                tileRecord("high", importance = 9),
            )
            assertEquals(listOf("high", "low", "box"), rank(records, near, "", SortMode.DISTANCE).map { it.origin })
        }

        @Test
        fun `similarity mode orders by score, then distance, ignoring importance`() {
            val records = listOf(
                tileRecord("far", "Alpha", importance = 9, locations = listOf(point(0.00005, 0.0))),
                tileRecord("close", "Alpha", importance = 1),
            )
            assertEquals(listOf("close", "far"), rank(records, near, "alpha", SortMode.SIMILARITY).map { it.origin })
        }

        @Test
        fun `keeps input order for hits that tie on everything`() {
            val records = listOf(tileRecord(null, "One"), tileRecord(null, "Two"), tileRecord(null, "Three"))
            assertEquals(listOf("One", "Two", "Three"), rank(records, near, "", SortMode.FUSED).map(::nameOf))
        }

        @Test
        fun `compares numbers like the reference's subtraction, with NaN last`() {
            assertEquals(0, compareNumbers(-0.0, 0.0))
            assertEquals(-1, compareNumbers(1.0, 2.0))
            assertEquals(1, compareNumbers(Double.NaN, Double.POSITIVE_INFINITY))
            assertEquals(0, compareNumbers(Double.NaN, Double.NaN))
        }
    }
}
