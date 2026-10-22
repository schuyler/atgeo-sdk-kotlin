package org.atgeo.client

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import java.io.IOException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val region = Region.Bbox(-122.42, 37.77, -122.41, 37.78)

private fun twoTiles(failTiles: Set<String> = emptySet()) = FakeServer(
    failTiles = failTiles,
    tilesets = listOf(
        overture(
            listOf(
                FakeTile(tileUrl("a"), listOf(record(place("a", "A", listOf(point(37.772, -122.412)))))),
                FakeTile(tileUrl("b"), listOf(record(place("b", "B", listOf(point(37.774, -122.414)))))),
            ),
        ),
    ),
)

class TransportTest {
    @Nested
    @DisplayName("failed tiles")
    inner class FailedTiles {
        @Test
        fun `reports a complete search`() = runTest {
            val result = client(twoTiles()).searchPlaces(region)
            assertEquals(true, result.complete)
            assertEquals(SearchResult.TileCounts(fetched = 2, failed = 0), result.tiles)
        }

        @Test
        fun `returns surviving results, marked incomplete, when a tile answers non-200`() = runTest {
            val result = client(twoTiles(setOf(tileUrl("b")))).searchPlaces(region)
            assertEquals(false, result.complete)
            assertEquals(SearchResult.TileCounts(fetched = 1, failed = 1), result.tiles)
            assertEquals(listOf(placeOrigin("a")), result.places.map { it.origin })
        }

        @Test
        fun `returns an incomplete result when a tile fetch rejects`() = runTest {
            val server = twoTiles()
            val flaky = HttpTransport { url -> if (url == tileUrl("a")) throw IOException("boom") else server.get(url) }
            val result = client(flaky).searchPlaces(region)
            assertEquals(false, result.complete)
            assertEquals(SearchResult.TileCounts(fetched = 1, failed = 1), result.tiles)
            assertEquals(listOf(placeOrigin("b")), result.places.map { it.origin })
        }

        @Test
        fun `does not retry a failed tile`() = runTest {
            val server = twoTiles(setOf(tileUrl("b")))
            client(server).searchPlaces(region)
            assertEquals(1, server.requests.count { it == tileUrl("b") })
        }

        @Test
        fun `reports every tile failed without throwing`() = runTest {
            val result = client(twoTiles(setOf(tileUrl("a"), tileUrl("b")))).searchPlaces(region)
            assertEquals(emptyList(), result.places)
            assertEquals(false, result.complete)
            assertEquals(SearchResult.TileCounts(fetched = 0, failed = 2), result.tiles)
        }
    }

    @Nested
    @DisplayName("typed server errors")
    inner class TypedServerErrors {
        @Test
        fun `surfaces BboxTooLarge from getTiles`() = runTest {
            val server = FakeServer(tilesFor = { TilesAnswer.Error("BboxTooLarge", "bbox too large") })
            val error = assertFailsWith<AtgeoException> { client(server).searchPlaces(region) }
            assertEquals("BboxTooLarge", error.code)
            assertEquals(400, error.status)
        }

        @Test
        fun `surfaces CollectionNotFound from getTiles`() = runTest {
            val error = assertFailsWith<AtgeoException> { client(FakeServer()).searchPlaces(region, collection = "nope") }
            assertEquals("CollectionNotFound", error.code)
        }

        @Test
        fun `surfaces DatasetNotFound from getTiles`() = runTest {
            val error = assertFailsWith<AtgeoException> { client(FakeServer()).searchPlaces(region, dataset = "example.org") }
            assertEquals("DatasetNotFound", error.code)
        }

        @TestFactory
        fun `throws when an antimeridian half is BboxTooLarge`(): List<DynamicTest> =
            listOf("first" to "170", "second" to "-180").map { (half, prefix) ->
                dynamicTest("throws when the $half antimeridian half is BboxTooLarge") {
                    runTest {
                        val server = FakeServer(
                            tilesets = listOf(overture(listOf(FakeTile(tileUrl("a"), listOf(record(place("a", "A", listOf(point(15.0, 175.0))))))))),
                            tilesFor = { bbox ->
                                if (bbox.startsWith(prefix)) TilesAnswer.Error("BboxTooLarge", "too large") else TilesAnswer.Urls(listOf(tileUrl("a")))
                            },
                        )
                        val error = assertFailsWith<AtgeoException> { client(server).searchPlaces(Region.Bbox(170.0, 10.0, -170.0, 20.0)) }
                        assertEquals("BboxTooLarge", error.code)
                    }
                }
            }

        @Test
        fun `does not subdivide after BboxTooLarge`() = runTest {
            val server = FakeServer(tilesFor = { TilesAnswer.Error("BboxTooLarge", "too large") })
            runCatching { client(server).searchPlaces(region) }
            assertEquals(1, server.requests.size)
        }
    }

    @Nested
    @DisplayName("XRPC URLs")
    inner class XrpcUrls {
        @Test
        @DisplayName("defaults to places.atgeo.org")
        fun defaultsToPlacesAtgeoOrg() = runTest {
            val server = twoTiles()
            client(server).searchPlaces(region)
            val u = server.requests[0]
            assertEquals("$DEFAULT_SERVER/xrpc/org.atgeo.getTiles", endpointOf(u))
            assertEquals("overturemaps.org", paramsOf(u)["dataset"])
            assertEquals("place", paramsOf(u)["collection"])
            assertEquals("-122.42,37.77,-122.41,37.78", paramsOf(u)["bbox"])
        }

        @Test
        fun `targets a custom server for every XRPC call`() = runTest {
            val base = "http://localhost:8000"
            val server = FakeServer(
                server = base,
                tilesets = listOf(overture(listOf(FakeTile("$base/tiles/a.json", listOf(record(place("a", "A", listOf(point(37.772, -122.412))))))))),
            )
            val client = client(server, server = base)
            client.searchPlaces(region)
            client.describeService()
            client.describeTileset("overture-place")
            runCatching { client.getPlace(placeOrigin("zzz")) }
            assertEquals(
                listOf(
                    "$base/xrpc/org.atgeo.getTiles",
                    "$base/xrpc/org.atgeo.describeService",
                    "$base/xrpc/org.atgeo.describeTileset",
                    "$base/xrpc/org.atgeo.getPlace",
                ),
                server.requests.filter { "/xrpc/" in it }.map(::endpointOf),
            )
            assert("$base/tiles/a.json" in server.requests)
        }
    }

    @Nested
    @DisplayName("XRPC layer (no reference counterpart)")
    inner class XrpcLayer {
        @Test
        fun `strips trailing slashes from the server and encodes query values`() {
            val xrpc = Xrpc("https://example.org//", { error("unused") })
            assertEquals(
                "https://example.org/xrpc/org.atgeo.getPlace?uri=a%2Cb%3Ac%20d%26e%3Df%23g%2Bh%25i%7Ej*k%2Fl",
                xrpc.url("org.atgeo.getPlace", listOf("uri" to "a,b:c d&e=f#g+h%i~j*k/l")),
            )
            assertEquals("https://example.org/xrpc/org.atgeo.describeService", xrpc.url("org.atgeo.describeService", emptyList()))
        }

        @Test
        fun `encodes non-ASCII as UTF-8`() {
            assertEquals("M%C3%BCnchen%20%F0%9F%98%80", encodeQueryValue("München 😀"))
        }

        @Test
        fun `fetches a tile URL exactly as given and returns a 2xx body`() = runTest {
            val asked = mutableListOf<String>()
            val xrpc = Xrpc("https://example.org", { url -> asked += url; HttpResponse(204, "x".toByteArray()) })
            assertContentEquals("x".toByteArray(), xrpc.get("https://tiles.example/a b.json?q=1"))
            assertEquals(listOf("https://tiles.example/a b.json?q=1"), asked)
        }

        @TestFactory
        fun `reads each error field on its own`(): List<DynamicTest> = listOf(
            """{"error": "BboxTooLarge", "message": "too big"}""" to ("BboxTooLarge" to "too big"),
            """{"error": "BboxTooLarge"}""" to ("BboxTooLarge" to "HTTP 400"),
            """{"message": "too big"}""" to ("HttpError" to "too big"),
            """{"error": 7, "message": ["x"]}""" to ("HttpError" to "HTTP 400"),
            """null""" to ("HttpError" to "HTTP 400"),
            """["BboxTooLarge"]""" to ("HttpError" to "HTTP 400"),
            """upstream error""" to ("HttpError" to "HTTP 400"),
            "" to ("HttpError" to "HTTP 400"),
        ).map { (body, expected) ->
            dynamicTest(body.ifEmpty { "(empty)" }) {
                runTest {
                    val xrpc = Xrpc("https://example.org", { HttpResponse(400, body.toByteArray()) })
                    val error = assertFailsWith<AtgeoException> { xrpc.query("org.atgeo.getTiles") }
                    assertEquals(expected, error.code to error.message)
                    assertEquals(400, error.status)
                }
            }
        }

        @TestFactory
        fun `carries the status, whatever the body says`(): List<DynamicTest> = listOf(
            404 to """{"error": "PlaceNotFound"}""",
            429 to "",
            503 to """{"error": "InvalidRequest", "message": "a 5xx with a 4xx-sounding code"}""",
        ).map { (status, body) ->
            dynamicTest("$status") {
                runTest {
                    val xrpc = Xrpc("https://example.org", { HttpResponse(status, body.toByteArray()) })
                    assertEquals(status, assertFailsWith<AtgeoException> { xrpc.query("org.atgeo.getPlace") }.status)
                }
            }
        }

        @Test
        fun `raises SerializationException for a 2xx body that is not JSON`() = runTest {
            val xrpc = Xrpc("https://example.org", { HttpResponse(200, "upstream error".toByteArray()) })
            assertFailsWith<SerializationException> { xrpc.query("org.atgeo.describeService") }
        }

        // The tree parser reads a lone unquoted token as a primitive (CONFORMANCE.md); every reader
        // then rejects it for not being an object, so the call still fails the same way.
        @Test
        fun `raises SerializationException for a 2xx body that is a lone unquoted token`() = runTest {
            val xrpc = Xrpc("https://example.org", { HttpResponse(200, "<html>".toByteArray()) })
            assertFailsWith<SerializationException> { readServiceDescription(xrpc.query("org.atgeo.describeService")) }
        }
    }
}
