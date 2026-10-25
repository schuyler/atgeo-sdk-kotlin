package org.atgeo.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// fixtures/lenient-readers.json (contract §13): a body for each of the five readers of §1 and either what
// the reader keeps of it, in JSON terms, or `decodingError`. A missing key is an absent field, so each
// reader's result is mapped onto JSON with the nulls left out and compared strictly.

private fun obj(vararg fields: Pair<String, JsonElement?>): JsonObject = JsonObject(fields.mapNotNull { (k, v) -> v?.let { k to it } }.toMap())

private fun str(s: String?): JsonElement? = s?.let(::JsonPrimitive)

private fun num(n: Long?): JsonElement? = n?.let(::JsonPrimitive)

private fun strings(list: List<String>?): JsonElement? = list?.let { JsonArray(it.map(::JsonPrimitive)) }

private fun <T> list(items: List<T>?, f: (T) -> JsonElement): JsonElement? = items?.let { JsonArray(it.map(f)) }

private fun Attribution.json() = obj(
    "dataset" to str(dataset), "collection" to str(collection), "release" to str(release), "source" to str(source),
    "licenses" to strings(licenses), "attribution" to str(attribution),
)

private fun CollectionRef.json() = obj("dataset" to str(dataset), "collection" to str(collection))

private fun CollectionAttribution.json() = obj(
    "dataset" to str(dataset), "collection" to str(collection), "release" to str(release), "source" to str(source),
    "licenses" to strings(licenses), "attribution" to str(attribution),
)

private fun CollectionInfo.json() = obj(
    "dataset" to str(dataset), "collection" to str(collection), "release" to str(release), "source" to str(source),
    "licenses" to strings(licenses), "attribution" to str(attribution), "recordCount" to num(recordCount),
    "locationFormats" to strings(locationFormats), "containmentTypes" to strings(containmentTypes),
    "categories" to list(categories) { obj("value" to str(it.value), "count" to num(it.count)) },
    "attributes" to strings(attributes),
)

private fun TileRecord.json() = obj("value" to value, "uri" to str(uri), "importance" to num(importance))

private val readers: Map<String, (JsonElement) -> JsonElement> = mapOf(
    "getTiles" to { body -> readTileList(body).let { obj("tiles" to strings(it.tiles), "overview" to JsonPrimitive(it.overview)) } },
    "tilePayload" to { body ->
        readTilePayload(body).let { obj("records" to list(it.records) { r -> r.json() }, "attribution" to list(it.attribution) { a -> a.json() }) }
    },
    "getPlace" to { body ->
        readPlace(body).let { obj("value" to it.value, "importance" to num(it.importance), "attribution" to list(it.attribution) { a -> a.json() }) }
    },
    "describeService" to { body ->
        readServiceDescription(body).let {
            obj(
                "did" to str(it.did), "maxTiles" to num(it.maxTiles),
                "tilesets" to list(it.tilesets) { t -> obj("slug" to str(t.slug), "collections" to list(t.collections) { c -> c.json() }) },
            )
        }
    },
    "describeTileset" to { body ->
        readTileset(body).let {
            obj(
                "slug" to str(it.slug), "publishedAt" to str(it.publishedAt),
                "extent" to it.extent?.let { e -> obj("north" to str(e.north), "south" to str(e.south), "east" to str(e.east), "west" to str(e.west)) },
                "collections" to list(it.collections) { c -> c.json() },
                "references" to list(it.references) { r -> r.json() },
                "overview" to it.overview?.let { o -> obj("records" to num(o.records)) },
            )
        }
    },
)

class LenientReadersTest {
    private val cases = (fixture("lenient-readers.json") as JsonArray).map { it as JsonObject }

    @Test
    fun `has the 104 cases the contract describes, and one for every reader`() {
        assertEquals(104, cases.size)
        assertEquals(readers.keys, cases.map { it.getValue("reader").stringOrNull() }.toSet())
    }

    @TestFactory
    fun `the lenient readers against fixtures-lenient-readers`(): List<DynamicTest> = cases.map { case ->
        val reader = case.getValue("reader").stringOrNull()!!
        dynamicTest("$reader: ${case.getValue("name").stringOrNull()}") {
            val read = readers.getValue(reader)
            val body = case.getValue("body")
            if (case["decodingError"]?.let { (it as JsonPrimitive).boolean } == true) {
                assertFailsWith<AtgeoDecodingException> { read(body) }
            } else {
                assertEquals(case.getValue("result"), read(body))
            }
        }
    }
}
