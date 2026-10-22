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
        val result = SearchResult(listOf(hit(0.5)), true, false, SearchResult.TileCounts(1, 0), emptyList())
        assertEquals(result, SearchResult(listOf(hit(0.5)), true, false, SearchResult.TileCounts(1, 0), emptyList()))
        val failure = TileFailure("https://t/a.json", TileFailure.Kind.STATUS, 503, AtgeoException("HttpError", "HTTP 503", 503))
        assertNotEquals(result, SearchResult(listOf(hit(0.5)), false, false, SearchResult.TileCounts(1, 1), listOf(failure)))
        assertEquals(
            "SearchResult(places=[], complete=false, overview=false, tiles=TileCounts(fetched=0, failed=1), tileFailures=" +
                "[TileFailure(url=https://t/a.json, kind=STATUS, status=503, error=org.atgeo.client.AtgeoException: HTTP 503)])",
            SearchResult(emptyList(), false, false, SearchResult.TileCounts(0, 1), listOf(failure)).toString(),
        )
    }

    @Test
    fun `a tile failure compares and hashes by url, kind and status, never by its error`() {
        fun failure(kind: TileFailure.Kind, status: Int?, error: Throwable) = TileFailure("https://t/a.json", kind, status, error)
        val a = failure(TileFailure.Kind.TRANSPORT, null, java.io.IOException("connection reset"))
        val b = failure(TileFailure.Kind.TRANSPORT, null, IllegalStateException("something else entirely"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, failure(TileFailure.Kind.DECODE, null, a.error))
        assertNotEquals(failure(TileFailure.Kind.STATUS, 500, a.error), failure(TileFailure.Kind.STATUS, 502, a.error))
        assertNotEquals(a, TileFailure("https://t/b.json", TileFailure.Kind.TRANSPORT, null, a.error))
        val results = listOf(a, b).map { SearchResult(emptyList(), false, false, SearchResult.TileCounts(0, 1), listOf(it)) }
        assertEquals(results[0], results[1])
        assertEquals(results[0].hashCode(), results[1].hashCode())
    }
}
