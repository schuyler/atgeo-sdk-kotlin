package org.atgeo.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
            val result = client(twoTiles()).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertEquals(true, result.complete)
            assertEquals(SearchResult.TileCounts(fetched = 2, failed = 0), result.tiles)
        }

        @Test
        fun `returns surviving results, marked incomplete, when a tile answers non-200`() = runTest {
            val result = client(twoTiles(setOf(tileUrl("b")))).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertEquals(false, result.complete)
            assertEquals(SearchResult.TileCounts(fetched = 1, failed = 1), result.tiles)
            assertEquals(listOf(placeOrigin("a")), result.places.map { it.origin })
        }

        @Test
        fun `returns an incomplete result when a tile fetch rejects`() = runTest {
            val server = twoTiles()
            val flaky = HttpTransport { url -> if (url == tileUrl("a")) throw IOException("boom") else server.get(url) }
            val result = client(flaky).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertEquals(false, result.complete)
            assertEquals(SearchResult.TileCounts(fetched = 1, failed = 1), result.tiles)
            assertEquals(listOf(placeOrigin("b")), result.places.map { it.origin })
        }

        @Test
        fun `does not retry a failed tile`() = runTest {
            val server = twoTiles(setOf(tileUrl("b")))
            client(server).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertEquals(1, server.requests.count { it == tileUrl("b") })
        }

        @Test
        fun `reports every tile failed without throwing`() = runTest {
            val result = client(twoTiles(setOf(tileUrl("a"), tileUrl("b")))).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertEquals(emptyList(), result.places)
            assertEquals(false, result.complete)
            assertEquals(SearchResult.TileCounts(fetched = 0, failed = 2), result.tiles)
        }
    }

    @Nested
    @DisplayName("tile failures (no reference counterpart: the reference reports none)")
    inner class TileFailures {
        private val ok = tileUrl("b")
        private val okTile = FakeTile(ok, listOf(record(place("b", "B", listOf(point(37.774, -122.414))))))

        /** A server listing `urls`, serving `okTile`, and answering anything in `responses` as given. */
        private fun serverFor(urls: List<String>, responses: Map<String, HttpResponse> = emptyMap()) =
            FakeServer(tilesets = listOf(overture(listOf(okTile))), tilesFor = { TilesAnswer.Urls(urls) }, responses = responses)

        private fun SearchResult.summary() = tileFailures.map { Triple(it.url, it.kind, it.status) }

        @Test
        fun `is empty when every tile loads`() = runTest {
            val result = client(twoTiles()).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertEquals(emptyList(), result.tileFailures)
            assertEquals(true, result.complete)
        }

        @Test
        fun `reports a refused URL, with no request`() = runTest {
            val server = serverFor(listOf("file:///etc/passwd", ok))
            val result = client(server).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertEquals(listOf(Triple("file:///etc/passwd", TileFailure.Kind.REFUSED, null)), result.summary())
            assertIs<java.net.MalformedURLException>(result.tileFailures.single().error)
            assertEquals(listOf(ok), server.requests.filter { "/xrpc/" !in it })
        }

        @TestFactory
        fun `reports a non-2xx as STATUS with its status and AtgeoException`(): List<DynamicTest> = listOf(
            404 to """{"error": "TileNotFound", "message": "gone"}""",
            500 to "upstream error",
            503 to "",
        ).map { (status, body) ->
            dynamicTest("$status") {
                runTest {
                    val bad = tileUrl("a")
                    val result = client(serverFor(listOf(bad, ok), mapOf(bad to HttpResponse(status, body.toByteArray())))).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
                    assertEquals(listOf(Triple(bad, TileFailure.Kind.STATUS, status)), result.summary())
                    val error = assertIs<AtgeoException>(result.tileFailures.single().error)
                    assertEquals(status, error.status)
                    assertEquals(listOf(placeOrigin("b")), result.places.map { it.origin })
                }
            }
        }

        @TestFactory
        fun `reports a 2xx that is not a usable payload as DECODE`(): List<DynamicTest> = listOf(
            "upstream error",
            "",
            "[]",
            "null",
            """{"records": {}}""",
            """{"tiles": []}""",
            // kotlinx.serialization refuses this with a SerializationException rather than overflowing the stack.
            """{"records": [""" + "[".repeat(100_000) + "]".repeat(100_000) + "]}",
        ).map { body ->
            dynamicTest(body.ifEmpty { "(empty)" }.take(40)) {
                runTest {
                    val bad = tileUrl("a")
                    val result = client(serverFor(listOf(bad, ok), mapOf(bad to HttpResponse(200, body.toByteArray())))).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
                    assertEquals(listOf(Triple(bad, TileFailure.Kind.DECODE, null)), result.summary())
                    assertIs<AtgeoDecodingException>(result.tileFailures.single().error)
                }
            }
        }

        @Test
        fun `reports whatever the transport throws as TRANSPORT, with that error`() = runTest {
            val server = serverFor(listOf(tileUrl("a"), ok, tileUrl("c")))
            val thrown = mapOf(tileUrl("a") to IOException("connection reset"), tileUrl("c") to IllegalStateException("a bug in the app's transport"))
            val transport = HttpTransport { url -> thrown[url]?.let { throw it } ?: server.get(url) }
            val result = client(transport).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertEquals(
                listOf(Triple(tileUrl("a"), TileFailure.Kind.TRANSPORT, null), Triple(tileUrl("c"), TileFailure.Kind.TRANSPORT, null)),
                result.summary(),
            )
            assertSame<Throwable?>(thrown[tileUrl("a")], result.tileFailures[0].error)
            assertSame<Throwable?>(thrown[tileUrl("c")], result.tileFailures[1].error)
        }

        @Test
        fun `reports an AtgeoException thrown by the transport as TRANSPORT, without a status`() = runTest {
            val server = serverFor(listOf(tileUrl("a"), ok))
            val thrown = AtgeoException("Teapot", "I'm a teapot", 418)
            val transport = HttpTransport { url -> if (url == tileUrl("a")) throw thrown else server.get(url) }
            val result = client(transport).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertEquals(listOf(Triple(tileUrl("a"), TileFailure.Kind.TRANSPORT, null)), result.summary())
            assertSame<Throwable>(thrown, result.tileFailures.single().error)
        }

        @Test
        fun `reports a transport's own timeout as TRANSPORT`() = runTest {
            val server = serverFor(listOf(tileUrl("a"), ok))
            val transport = HttpTransport { url -> if (url == tileUrl("a")) withTimeout(10) { awaitCancellation() } else server.get(url) }
            val result = client(transport).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertEquals(listOf(Triple(tileUrl("a"), TileFailure.Kind.TRANSPORT, null)), result.summary())
            assertIs<java.net.SocketTimeoutException>(result.tileFailures.single().error)
        }

        @Test
        fun `lists failures in URL order, not the order they failed in`() = runTest {
            val urls = listOf("ftp://tiles.example/0.json", tileUrl("a"), ok, tileUrl("c"), tileUrl("d"), tileUrl("e"))
            val server = serverFor(
                urls,
                mapOf(tileUrl("a") to HttpResponse(502, ByteArray(0)), tileUrl("d") to HttpResponse(200, "nope".toByteArray())),
            )
            // The earlier a URL sorts, the later it finishes.
            val transport = HttpTransport { url ->
                if ("/xrpc/" !in url) delay(1_000L * (urls.size - urls.indexOf(url)))
                if (url == tileUrl("c")) throw IOException("reset")
                server.get(url)
            }
            val result = client(transport).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertEquals(
                listOf(
                    Triple("ftp://tiles.example/0.json", TileFailure.Kind.REFUSED, null),
                    Triple(tileUrl("a"), TileFailure.Kind.STATUS, 502),
                    Triple(tileUrl("c"), TileFailure.Kind.TRANSPORT, null),
                    Triple(tileUrl("d"), TileFailure.Kind.DECODE, null),
                    Triple(tileUrl("e"), TileFailure.Kind.STATUS, 404),
                ),
                result.summary(),
            )
            assertEquals(SearchResult.TileCounts(fetched = 1, failed = 5), result.tiles)
            assertEquals(result.tiles.failed, result.tileFailures.size)
            assertEquals(false, result.complete)
        }

        @Test
        fun `makes two results with the same failures equal, whatever their errors`() = runTest {
            var n = 0
            val server = serverFor(listOf(tileUrl("a"), ok))
            val transport = HttpTransport { url -> if (url == tileUrl("a")) throw IOException("failure ${++n}") else server.get(url) }
            val c = client(transport)
            val first = c.searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            val second = c.searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertNotSame(first.tileFailures.single().error, second.tileFailures.single().error)
            assertEquals(first, second)
            assertEquals(first.hashCode(), second.hashCode())
        }

        // A transport may answer its caller's cancellation with an error of its own, as OkHttp does with
        // IOException("Canceled"). The search still fails with the cancellation, and its workers stop
        // there instead of counting those as failed tiles and going on to claim the remaining URLs.
        @Test
        fun `does not turn the search's cancellation into failed tiles`() = runBlocking {
            val server = manyTilesServer()
            val started = CompletableDeferred<Unit>()
            val inFlight = AtomicInteger()
            val transport = HttpTransport { url ->
                if ("/xrpc/" in url) return@HttpTransport server.get(url)
                if (inFlight.incrementAndGet() == 8) started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    @Suppress("ThrowFromFinallyBlock")
                    throw IOException("Canceled")
                }
            }
            val client = AtgeoClient(transport = transport, computeDispatcher = Dispatchers.Default)
            val search = async(Dispatchers.Default) { client.searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION) }
            withTimeout(5_000) { started.await() }
            search.cancel()
            assertIs<CancellationException>(runCatching { search.await() }.exceptionOrNull())
            assertTrue(search.isCancelled)
            delay(100)
            assertEquals(8, inFlight.get())
        }

        private fun manyTilesServer() = FakeServer(
            tilesets = listOf(overture((0 until 12).map { i -> FakeTile(tileUrl("t%02d".format(i)), listOf(record(place("p$i", "P$i", listOf(point(37.775, -122.415)))))) })),
        )
    }

    // Contract §4.4: fetch's Request refuses these before any request. Before the check, the default
    // transport failed a relative or javascript: URL with MalformedURLException and a file: or ftp: URL
    // with ClassCastException, and fetched a URL with credentials (dropping them) as a used tile.
    @Nested
    @DisplayName("refused tile URLs (no reference counterpart)")
    inner class RefusedTileUrls {
        private val emptyTile = HttpResponse(200, """{"records": []}""".toByteArray())

        @TestFactory
        fun `fails the tile without a request`(): List<DynamicTest> = listOf(
            "/tiles/a.json",
            "tiles/a.json",
            "//tiles.example/a.json",
            "file:///etc/passwd",
            "ftp://tiles.example/a.json",
            "javascript:alert(1)",
            "data:application/json,{}",
            "https://user:pw@tiles.example/a.json",
            "https://user@tiles.example/a.json",
            "https://:pw@tiles.example/a.json",
            "https://@tiles.example/a.json",
            "HTTPS://U:P@[::1]:8443/a.json",
            "https://tiles.example\\@evil.example/a.json",
            "https:///a.json",
            "https://",
            "https://:443/a.json",
            "https:tiles.example/a.json",
            "httpſ://tiles.example/a.json",
            // Backslash and slash mixes WHATWG reads as https://h/x; the check wants exactly ://.
            "https:\\\\tiles.example/a.json",
            "https:/\\tiles.example/a.json",
            "https:\\/tiles.example/a.json",
            "https://\\tiles.example/a.json",
            // Ports WHATWG rejects: java.net.URL would fetch :+80.
            "http://127.0.0.1:+80/a.json",
            "http://tiles.example:-1/a.json",
            "http://tiles.example:65536/a.json",
            "http://tiles.example:8a/a.json",
            "http://tiles.example:\u0668\u0660/a.json",
            "http://tiles.example:80:90/a.json",
            // Forbidden host code points.
            "https://tiles example/a.json",
            "https://tiles<.example/a.json",
            "https://tiles>.example/a.json",
            "https://tiles%41.example/a.json",
            "https://tiles^.example/a.json",
            "https://tiles|.example/a.json",
            "https://tiles\u0000.example/a.json",
            "https://tiles\u007F.example/a.json",
            "https://tiles].example/a.json",
            // Malformed IPv6.
            "https://[::1/a.json",
            "https://[::1]x/a.json",
            "https://[fe80::1%25eth0]/a.json",
            "https://[g::1]/a.json",
            "https://[]/a.json",
        ).map { url ->
            dynamicTest(url) {
                runTest {
                    val asked = mutableListOf<String>()
                    val server = FakeServer(tilesFor = { TilesAnswer.Urls(listOf(url)) })
                    val transport = HttpTransport { u -> if ("/xrpc/" in u) server.get(u) else { asked += u; emptyTile } }
                    val result = client(transport).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
                    assertEquals(SearchResult.TileCounts(fetched = 0, failed = 1), result.tiles)
                    assertEquals(false, result.complete)
                    assertEquals(emptyList(), asked)
                }
            }
        }

        @TestFactory
        fun `fetches an http or https URL exactly as given`(): List<DynamicTest> = listOf(
            "https://tiles.example/a.json",
            "http://tiles.example:8080/a.json?v=1#f",
            "HTTPS://Tiles.Example/a.json",
            "HtTp://tiles.example/a.json",
            "http://[::1]:8000/a.json",
            "https://[2001:db8::1]/a.json",
            "https://tiles.example/a@b.json?u=x@y#@",
            "https://tiles.example?x",
            "https://tiles.example",
            "https://tiles.example\\a.json",
            " https://tiles.example/a.json\n",
            "ht\ttps://tiles.example/a.json",
            "http://tiles.example:/a.json",
            "http://tiles.example:65535/a.json",
            "http://tiles.example:0000080/a.json",
            "https://[::ffff:1.2.3.4]:443/a.json",
            "https://bücher.example/a.json",
        ).map { url ->
            dynamicTest(url.replace("\n", "\\n").replace("\t", "\\t")) {
                runTest {
                    val asked = mutableListOf<String>()
                    val server = FakeServer(tilesFor = { TilesAnswer.Urls(listOf(url)) })
                    val transport = HttpTransport { u -> if ("/xrpc/" in u) server.get(u) else { asked += u; emptyTile } }
                    val result = client(transport).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
                    assertEquals(SearchResult.TileCounts(fetched = 1, failed = 0), result.tiles)
                    assertEquals(listOf(url), asked)
                }
            }
        }

        @Test
        fun `fails only the refused tile and keeps the rest`() = runTest {
            val good = tileUrl("a")
            val server = twoTiles()
            val urls = listOf("file:///etc/passwd", good)
            val transport = HttpTransport { u ->
                if ("/xrpc/" in u) FakeServer(tilesFor = { TilesAnswer.Urls(urls) }).get(u) else server.get(u)
            }
            val result = client(transport).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertEquals(SearchResult.TileCounts(fetched = 1, failed = 1), result.tiles)
            assertEquals(listOf(placeOrigin("a")), result.places.map { it.origin })
            assertEquals(listOf(good), server.requests)
        }
    }

    @Nested
    @DisplayName("typed server errors")
    inner class TypedServerErrors {
        @Test
        fun `surfaces BboxTooLarge from getTiles`() = runTest {
            val server = FakeServer(tilesFor = { TilesAnswer.Error("BboxTooLarge", "bbox too large") })
            val error = assertFailsWith<AtgeoException> { client(server).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION) }
            assertEquals("BboxTooLarge", error.code)
            assertEquals(400, error.status)
        }

        @Test
        fun `surfaces CollectionNotFound from getTiles`() = runTest {
            val error = assertFailsWith<AtgeoException> { client(FakeServer()).searchPlaces(region, OVERTURE_DATASET, collection = "nope") }
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
                        val error = assertFailsWith<AtgeoException> { client(server).searchPlaces(Region.Bbox(170.0, 10.0, -170.0, 20.0), OVERTURE_DATASET, collection = OVERTURE_COLLECTION) }
                        assertEquals("BboxTooLarge", error.code)
                    }
                }
            }

        @Test
        fun `does not subdivide after BboxTooLarge`() = runTest {
            val server = FakeServer(tilesFor = { TilesAnswer.Error("BboxTooLarge", "too large") })
            runCatching { client(server).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION) }
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
            client(server).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
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
            client.searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
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
        fun `raises AtgeoDecodingException for a 2xx body that is not JSON`() = runTest {
            val xrpc = Xrpc("https://example.org", { HttpResponse(200, "upstream error".toByteArray()) })
            val error = assertFailsWith<AtgeoDecodingException> { xrpc.query("org.atgeo.describeService") }
            // An IOException, so it is caught with network failures, never as an IllegalArgumentException.
            assertIs<IOException>(error)
            assertIs<SerializationException>(error.cause)
        }

        // The tree parser reads a lone unquoted token as a primitive (CONFORMANCE.md); every reader
        // then rejects it for not being an object, so the call still fails the same way.
        @Test
        fun `raises AtgeoDecodingException for a 2xx body that is a lone unquoted token`() = runTest {
            val xrpc = Xrpc("https://example.org", { HttpResponse(200, "<html>".toByteArray()) })
            assertFailsWith<AtgeoDecodingException> { readServiceDescription(xrpc.query("org.atgeo.describeService")) }
        }
    }
}
