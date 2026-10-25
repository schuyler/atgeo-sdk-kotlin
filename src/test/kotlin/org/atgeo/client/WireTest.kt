package org.atgeo.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

/** The lenient readers, directly (no reference counterpart: the reference has no such layer). */
class WireTest {
    @Nested
    @DisplayName("primitives")
    inner class Primitives {
        @Test
        fun `a string field reads only a JSON string`() {
            assertEquals("a", json("\"a\"").stringOrNull())
            for (text in listOf("5", "true", "null", "{}", "[]")) assertNull(json(text).stringOrNull(), text)
            assertNull(null.stringOrNull())
        }

        @TestFactory
        fun `importance and counts`(): List<DynamicTest> = listOf(
            "5" to 5L,
            "-5" to -5L,
            "1.9" to 1L,
            "-1.9" to -1L,
            "9223372036854774784" to 9223372036854774784L, // the largest double below 2^63, exact
            "9007199254740993" to 9007199254740992L, // precision lost through the double, as in JavaScript
            "9223372036854775807" to null, // rounds to 2^63
            "-9223372036854775808" to null, // -2^63: the check is symmetric
            "1e400" to null,
            "\"5\"" to null,
            "true" to null,
            "null" to null,
            "[5]" to null,
            "{}" to null,
        ).map { (text, expected) -> dynamicTest(text) { assertEquals(expected, json(text).int64OrNull()) } }

        @TestFactory
        fun `overview truthiness`(): List<DynamicTest> = listOf(
            "null" to false, "false" to false, "0" to false, "-0" to false, "0.0" to false, "\"\"" to false,
            "true" to true, "1" to true, "-1" to true, "\"0\"" to true, "\"false\"" to true, "[]" to true, "{}" to true,
        ).map { (text, expected) -> dynamicTest(text) { assertEquals(expected, json(text).truthy()) } }

        @Test
        fun `an absent overview is false`() {
            assertEquals(false, null.truthy())
        }

        // CONFORMANCE.md: the tree parser accepts unquoted literals that JSON.parse rejects.
        @Test
        fun `an unquoted literal the tree parser accepts is truthy`() {
            val body = json("""{"tiles": [], "overview": yes}""")
            assertEquals(true, readTileList(body).overview)
        }
    }

    @Nested
    @DisplayName("tile payloads")
    inner class TilePayloads {
        @Test
        fun `keeps value, uri and importance and nothing else`() {
            val records = readTilePayload(
                json("""{"publishedAt": "x", "records": [{"value": {"b": 1, "a": {"z": [2.50]}}, "uri": "u", "importance": 3, "extra": 4}]}"""),
            ).records
            assertEquals(listOf(TileRecord(json("""{"b": 1, "a": {"z": [2.50]}}""") as JsonObject, "u", 3)), records)
            assertEquals(listOf("b", "a"), records.single().value.keys.toList())
            assertEquals("2.50", ((records.single().value["a"] as JsonObject)["z"] as kotlinx.serialization.json.JsonArray)[0].toString())
        }

        @Test
        fun `skips records that are not objects or have no object value, and keeps the tile`() {
            val records = readTilePayload(
                json("""{"records": [7, "s", [1], null, {"value": null}, {}, {"value": 5}, {"value": {"name": "ok"}}]}"""),
            ).records
            assertEquals(listOf("ok"), records.map { it.value["name"].stringOrNull() })
        }

        @Test
        fun `reads a wrong-typed uri or importance as absent`() {
            val records = readTilePayload(
                json("""{"records": [{"value": {}, "uri": {}, "importance": "7"}, {"value": {}, "uri": 9, "importance": [1]}]}"""),
            ).records
            assertEquals(listOf(TileRecord(JsonObject(emptyMap())), TileRecord(JsonObject(emptyMap()))), records)
        }

        @Test
        fun `keeps the attribution entries that name a dataset and a collection, in served order, duplicates included`() {
            val payload = readTilePayload(
                json(
                    """{"records": [], "attribution": [null, 5, "x", [], {}, {"dataset": "d"}, {"collection": "c"}, {"dataset": 1, "collection": "c"},
                        {"dataset": "z", "collection": "c", "release": "r", "source": "s", "licenses": ["l", 1, null], "attribution": "a", "extra": 1},
                        {"dataset": "a", "collection": "c", "release": 5, "licenses": "l"}, {"dataset": "z", "collection": "c"}]}""",
                ),
            )
            assertEquals(
                listOf(
                    Attribution("z", "c", release = "r", source = "s", licenses = listOf("l"), attribution = "a"),
                    Attribution("a", "c"),
                    Attribution("z", "c"),
                ),
                payload.attribution,
            )
        }

        @TestFactory
        fun `leaves the attribution absent unless it is an array`(): List<DynamicTest> =
            listOf("""{"records": []}""", """{"records": [], "attribution": null}""", """{"records": [], "attribution": {}}""",
                """{"records": [], "attribution": "x"}""", """{"records": [], "attribution": 5}""").map { text ->
                dynamicTest(text) { assertNull(readTilePayload(json(text)).attribution) }
            }

        @Test
        fun `reads an empty attribution array as an empty list, not absent`() {
            assertEquals(emptyList(), readTilePayload(json("""{"records": [], "attribution": []}""")).attribution)
        }

        @TestFactory
        fun `throws without a records array`(): List<DynamicTest> =
            listOf("{}", "[]", "null", "5", """{"records": {}}""", """{"records": "x"}""").map { text ->
                dynamicTest(text) { assertFailsWith<AtgeoDecodingException> { readTilePayload(json(text)) } }
            }
    }

    @Nested
    @DisplayName("getTiles")
    inner class GetTiles {
        @Test
        fun `skips non-string entries`() {
            assertEquals(TileList(listOf("a", "b"), false), readTileList(json("""{"tiles": ["a", 1, null, {}, "b"]}""")))
        }

        @TestFactory
        fun `throws without a tiles array`(): List<DynamicTest> =
            listOf("{}", "[]", "null", """{"tiles": "x"}""", """{"tiles": null}""").map { text ->
                dynamicTest(text) { assertFailsWith<AtgeoDecodingException> { readTileList(json(text)) } }
            }
    }

    @Nested
    @DisplayName("getPlace")
    inner class GetPlace {
        @Test
        fun `reads the value, importance and attribution, not the uri or url`() {
            val answer = readPlace(
                json("""{"uri": 5, "url": 6, "value": {"name": "x"}, "importance": 2.7, "attribution": [{"dataset": "d", "collection": "c", "licenses": ["l"]}]}"""),
            )
            assertEquals(PlaceAnswer(json("""{"name": "x"}""") as JsonObject, 2L, listOf(Attribution("d", "c", licenses = listOf("l")))), answer)
        }

        @Test
        fun `keeps the attribution in served order, duplicates included, unsorted`() {
            val answer = readPlace(
                json(
                    """{"value": {}, "attribution": [{"dataset": "z", "collection": "c"}, {"dataset": "a", "collection": "c", "release": "1"},
                        {"dataset": "a", "collection": "c", "release": "2"}, {"dataset": "z", "collection": "c"}]}""",
                ),
            )
            assertEquals(
                listOf(Attribution("z", "c"), Attribution("a", "c", release = "1"), Attribution("a", "c", release = "2"), Attribution("z", "c")),
                answer.attribution,
            )
        }

        @TestFactory
        fun `reads the attribution as empty unless it is an array`(): List<DynamicTest> =
            listOf("""{"value": {}}""", """{"value": {}, "attribution": null}""", """{"value": {}, "attribution": {}}""", """{"value": {}, "attribution": "x"}""").map { text ->
                dynamicTest(text) { assertEquals(emptyList(), readPlace(json(text)).attribution) }
            }

        @TestFactory
        fun `throws without an object value`(): List<DynamicTest> =
            listOf(
                "{}", "null", "[]", """{"value": null}""", """{"value": [1]}""", """{"value": "x"}""", """{"value": 5}""",
                """{"uri": "x", "record": {"name": "x"}}""", // the earlier shape
            ).map { text -> dynamicTest(text) { assertFailsWith<AtgeoDecodingException> { readPlace(json(text)) } } }
    }

    @Nested
    @DisplayName("describe types")
    inner class Describe {
        @Test
        fun `maps a complete describeService`() {
            val body = json("""{"did": "did:web:x", "maxTiles": 50, "tilesets": [{"slug": "s", "collections": [{"dataset": "d", "collection": "c"}]}], "new": 1}""")
            assertEquals(
                ServiceDescription("did:web:x", 50, listOf(ServiceDescription.ServedTileset("s", listOf(CollectionRef("d", "c"))))),
                readServiceDescription(body),
            )
        }

        @Test
        fun `reads missing and wrong-typed fields as absent and required lists as empty`() {
            assertEquals(ServiceDescription(null, null, emptyList()), readServiceDescription(json("""{"did": 7, "maxTiles": "50", "tilesets": "x"}""")))
            assertEquals(
                ServiceDescription(null, null, listOf(ServiceDescription.ServedTileset(null, emptyList()), ServiceDescription.ServedTileset("s", emptyList()))),
                readServiceDescription(json("""{"tilesets": [{}, 5, null, {"slug": "s", "collections": {}}]}""")),
            )
        }

        @Test
        fun `maps a describeTileset leniently`() {
            val body = json(
                """
                {"slug": "s", "publishedAt": "2026-01-01T00:00:00Z",
                 "extent": {"north": "1", "south": 2, "east": "3"}, "overview": {"records": 1234.9, "extra": 1},
                 "collections": [{"dataset": "d", "collection": "c", "recordCount": 12.9, "licenses": ["a", 1, "b"],
                                  "locationFormats": "x", "categories": [{"value": "v", "count": "3"}, 4, {}], "attributes": []}, "junk"],
                 "references": [{"dataset": "r", "licenses": null}]}
                """,
            )
            assertEquals(
                Tileset(
                    slug = "s",
                    publishedAt = "2026-01-01T00:00:00Z",
                    extent = Tileset.Extent(north = "1", east = "3"),
                    collections = listOf(
                        CollectionInfo(
                            dataset = "d",
                            collection = "c",
                            recordCount = 12,
                            licenses = listOf("a", "b"),
                            categories = listOf(CollectionInfo.CategoryCount("v", null), CollectionInfo.CategoryCount()),
                            attributes = emptyList(),
                        ),
                    ),
                    references = listOf(CollectionAttribution(dataset = "r")),
                    overview = Tileset.Overview(1234),
                ),
                readTileset(body),
            )
        }

        @Test
        fun `reads an extent that is not an object as absent and a missing collections list as empty`() {
            assertEquals(Tileset(), readTileset(json("""{"extent": [1], "collections": null}""")))
        }

        @TestFactory
        fun `reads an overview that is not an object as absent and its unreadable records as absent`(): List<DynamicTest> = listOf(
            "null" to null, "[]" to null, "5" to null, "\"x\"" to null, "{}" to Tileset.Overview(null),
            """{"records": "9"}""" to Tileset.Overview(null), """{"records": 7}""" to Tileset.Overview(7),
        ).map { (text, expected) -> dynamicTest("overview $text") { assertEquals(expected, readTileset(json("""{"overview": $text}""")).overview) } }

        @TestFactory
        fun `throws for a body that is not an object`(): List<DynamicTest> =
            listOf("null", "[]", "5", "\"x\"", "false").flatMap { text ->
                listOf(
                    dynamicTest("describeService $text") { assertFailsWith<AtgeoDecodingException> { readServiceDescription(json(text)) } },
                    dynamicTest("describeTileset $text") { assertFailsWith<AtgeoDecodingException> { readTileset(json(text)) } },
                )
            }
    }

    // Each row of CONFORMANCE.md's number-parsing table, read through locate().
    @Nested
    @DisplayName("coordinates (CONFORMANCE.md)")
    inner class Coordinates {
        private fun latitudeOf(raw: JsonElement): Double? {
            val loc = JsonObject(mapOf("\$type" to JsonPrimitive("community.lexicon.location.geo"), "latitude" to raw, "longitude" to JsonPrimitive("0")))
            return locate(JsonObject(mapOf("locations" to kotlinx.serialization.json.JsonArray(listOf(loc))))).point?.lat
        }

        @TestFactory
        fun `string forms`(): List<DynamicTest> = listOf(
            " 1.5 " to 1.5, "1.5f" to 1.5, "1.5d" to 1.5, "0x1p3" to 8.0,
            "1e" to null, "1.5abc" to null, "1_000" to null,
            "+1.5" to 1.5, ".5" to 0.5, "1." to 1.0, "-0" to -0.0, "1e2" to 100.0,
            "Infinity" to null, "NaN" to null, "" to null,
        ).map { (text, expected) -> dynamicTest("\"$text\"") { assertEquals(expected, latitudeOf(JsonPrimitive(text))) } }

        @Test
        fun `a JSON number works and an array does not`() {
            assertEquals(37.5, latitudeOf(json("37.5")))
            assertNull(latitudeOf(json("[0.5]")))
            assertNull(latitudeOf(json("true")))
            assertNull(latitudeOf(json("null")))
        }
    }
}
