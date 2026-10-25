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
            overview = Tileset.Overview(1234),
        )
        assertEquals(tileset, Json.decodeFromString<Tileset>(Json.encodeToString(tileset)))
        val service = ServiceDescription("did:web:example", 50, listOf(ServiceDescription.ServedTileset("overture", listOf(CollectionRef("a", "b")))))
        assertEquals(service, Json.decodeFromString<ServiceDescription>(Json.encodeToString(service)))
    }

    @Test
    fun `results compare, hash and print by value`() {
        fun hit(score: Double?) = SearchHit("urn:x", CollectionKey("d", "c"), JsonObject(mapOf("name" to JsonPrimitive("A"))), 5, 12.5, score)
        assertEquals(hit(0.5), hit(0.5))
        assertEquals(hit(0.5).hashCode(), hit(0.5).hashCode())
        assertNotEquals(hit(0.5), hit(null))
        assertEquals("SearchHit(origin=urn:x, collection=CollectionKey(dataset=d, collection=c), place={\"name\":\"A\"}, " +
            "importance=5, distance=12.5, score=0.5)", hit(0.5).toString())
        val result = SearchResult(listOf(hit(0.5)), true, false, SearchResult.TileCounts(1, 0), emptyList(), emptyList())
        assertEquals(result, SearchResult(listOf(hit(0.5)), true, false, SearchResult.TileCounts(1, 0), emptyList(), emptyList()))
        val failure = TileFailure("https://t/a.json", TileFailure.Kind.STATUS, 503, AtgeoException("HttpError", "HTTP 503", 503))
        assertNotEquals(result, SearchResult(listOf(hit(0.5)), false, false, SearchResult.TileCounts(1, 1), listOf(failure), emptyList()))
        assertEquals(
            "SearchResult(places=[], complete=false, overview=false, tiles=TileCounts(fetched=0, failed=1), tileFailures=" +
                "[TileFailure(url=https://t/a.json, kind=STATUS, status=503, error=org.atgeo.client.AtgeoException: HTTP 503)], attribution=[])",
            SearchResult(emptyList(), false, false, SearchResult.TileCounts(0, 1), listOf(failure), emptyList()).toString(),
        )
    }

    @Test
    fun `attribution, collections and places compare, hash and print by value`() {
        fun entry(release: String?, licenses: List<String>? = listOf("l")) = Attribution("d", "c", release, "s", licenses, "a")
        assertEquals(entry("r"), entry("r"))
        assertEquals(entry("r").hashCode(), entry("r").hashCode())
        assertNotEquals(entry("r"), entry("r2"))
        assertNotEquals(entry("r"), entry("r", licenses = null))
        assertNotEquals(entry("r"), entry("r", licenses = listOf("l", "m")))
        assertEquals("Attribution(dataset=d, collection=c, release=r, source=s, licenses=[l], attribution=a)", entry("r").toString())
        assertEquals(CollectionKey("d", "c"), CollectionKey("d", "c"))
        assertEquals(CollectionKey("d", "c").hashCode(), CollectionKey("d", "c").hashCode())
        assertNotEquals(CollectionKey("d", "c"), CollectionKey("d", "x"))
        assertNotEquals(CollectionKey("d", "c"), CollectionKey("x", "c"))
        val place = JsonObject(mapOf("name" to JsonPrimitive("A")))
        assertEquals(PlaceRecord("o", place, 1, listOf(entry("r"))), PlaceRecord("o", place, 1, listOf(entry("r"))))
        assertNotEquals(PlaceRecord("o", place, 1, listOf(entry("r"))), PlaceRecord("o", place, 1, emptyList()))
        assertEquals(
            "PlaceRecord(origin=o, place={\"name\":\"A\"}, importance=1, attribution=[${entry("r")}])",
            PlaceRecord("o", place, 1, listOf(entry("r"))).toString(),
        )
        val counts = SearchResult.TileCounts(0, 0)
        assertNotEquals(
            SearchResult(emptyList(), true, false, counts, emptyList(), listOf(entry("r"))),
            SearchResult(emptyList(), true, false, counts, emptyList(), emptyList()),
        )
        assertNotEquals(
            SearchHit("o", CollectionKey("d", "c"), place, null, 0.0, null),
            SearchHit("o", null, place, null, 0.0, null),
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
        val results = listOf(a, b).map { SearchResult(emptyList(), false, false, SearchResult.TileCounts(0, 1), listOf(it), emptyList()) }
        assertEquals(results[0], results[1])
        assertEquals(results[0].hashCode(), results[1].hashCode())
    }
}
