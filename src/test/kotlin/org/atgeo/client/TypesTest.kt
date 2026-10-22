package org.atgeo.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The public result and describe types are plain classes; they must still behave as values (no reference counterpart). */
class TypesTest {
    @Test
    fun `describe types survive a serialization round trip, as the README promises`() {
        val tileset = Tileset(
            slug = "overture",
            extent = Tileset.Extent("44", "42", "-70", "-72"),
            collections = listOf(
                CollectionInfo(dataset = "overturemaps.org", collection = "place", recordCount = 3, categories = listOf(CollectionInfo.CategoryCount("cafe", 2))),
            ),
            references = listOf(CollectionAttribution(dataset = "osm.org", licenses = listOf("ODbL-1.0"))),
        )
        assertEquals(tileset, Json.decodeFromString<Tileset>(Json.encodeToString(tileset)))
        val service = ServiceDescription("did:web:example", listOf(ServiceDescription.ServedTileset("overture", listOf(CollectionRef("a", "b")))))
        assertEquals(service, Json.decodeFromString<ServiceDescription>(Json.encodeToString(service)))
    }

    @Test
    fun `results compare, hash and print by value`() {
        fun hit(score: Double?) = SearchHit("urn:x", JsonObject(mapOf("name" to JsonPrimitive("A"))), 5, 12.5, score)
        assertEquals(hit(0.5), hit(0.5))
        assertEquals(hit(0.5).hashCode(), hit(0.5).hashCode())
        assertNotEquals(hit(0.5), hit(null))
        assertEquals("SearchHit(origin=urn:x, place={\"name\":\"A\"}, importance=5, distance=12.5, score=0.5)", hit(0.5).toString())
        val result = SearchResult(listOf(hit(0.5)), true, false, SearchResult.TileCounts(1, 0))
        assertEquals(result, SearchResult(listOf(hit(0.5)), true, false, SearchResult.TileCounts(1, 0)))
        assertNotEquals(result, SearchResult(listOf(hit(0.5)), false, false, SearchResult.TileCounts(1, 1)))
    }
}
