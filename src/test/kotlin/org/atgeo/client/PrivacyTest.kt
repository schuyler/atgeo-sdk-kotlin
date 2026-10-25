package org.atgeo.client

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TILE = "https://tiles.example/t1.json"

private fun server() = FakeServer(
    tilesets = listOf(overture(listOf(FakeTile(TILE, listOf(record(place("r1", "Zanzibar Quokka Cafe", listOf(point(37.775, -122.418))))))))),
)

// The seam carries only a URL: method and body are fixed by construction, and the default
// transport's headers are checked against a loopback server in HttpUrlConnectionTransportTest.
private fun wire(requests: List<String>): List<String> = requests.flatMap { url ->
    val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
    listOf(url.lowercase(), decoded.lowercase())
}

// encodeURIComponent, near enough for these needles: form encoding with spaces as %20.
private fun encodeURIComponent(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

class PrivacyTest {
    @Nested
    @DisplayName("search text stays local")
    inner class SearchText {
        @TestFactory
        fun `does not put the text on the wire`(): List<DynamicTest> =
            listOf("Zanzibar Quokka", "  ZANZIBÁR   quokka ", "Zanzibar").map { text ->
                dynamicTest("does not put \"$text\" on the wire") {
                    runTest {
                        val fake = server()
                        client(fake).searchPlaces(Region.Circle(37.7749295, -122.4194155, 500.0), OVERTURE_DATASET, text = text, collection = OVERTURE_COLLECTION)
                        assertTrue(fake.requests.isNotEmpty())
                        val normalized = "zanzibar quokka"
                        val needles = listOf(text.lowercase(), text.trim().lowercase(), normalized) + normalized.split(" ") + listOf("zanzib", "quokka")
                        for (haystack in wire(fake.requests)) {
                            for (needle in needles) assertFalse(needle in haystack, "\"$needle\" in $haystack")
                            assertFalse(encodeURIComponent(text).lowercase() in haystack)
                        }
                    }
                }
            }

        @Test
        fun `issues only getTiles and tile requests`() = runTest {
            val fake = server()
            client(fake).searchPlaces(Region.Circle(37.7749295, -122.4194155, 500.0), OVERTURE_DATASET, text = "Zanzibar Quokka", collection = OVERTURE_COLLECTION)
            val kinds = fake.requests.map { if ("/xrpc/org.atgeo.getTiles?" in it) "tiles" else if (it == TILE) "tile" else it }
            assertEquals(listOf("tiles", "tile"), kinds)
            // Every request is a GET: HttpTransport has no other method.
        }

        @Test
        fun `leaves every form of the text out of tileFailures`() = runTest {
            val tiles = listOf("a", "b", "c", "d").map { "https://tiles.example/$it.json" }
            val fake = FakeServer(
                failTiles = setOf(tiles[0]),
                tilesets = listOf(overture(tiles.map { FakeTile(it, listOf(record(place("r1", "Zanzibar Quokka Cafe", listOf(point(37.775, -122.418)))))) })),
            )
            val transport = HttpTransport { url ->
                when (url) {
                    tiles[1] -> HttpResponse(200, "<html>not a tile</html>".toByteArray())
                    tiles[2] -> throw java.io.IOException("fetch failed")
                    tiles[3] -> HttpResponse(500, "null".toByteArray())
                    else -> fake.get(url)
                }
            }
            val text = "  ZANZIBÁR   quokka "
            val result = client(transport).searchPlaces(Region.Circle(37.7749295, -122.4194155, 500.0), OVERTURE_DATASET, text = text, collection = OVERTURE_COLLECTION)
            assertEquals(
                listOf(TileFailure.Kind.STATUS, TileFailure.Kind.DECODE, TileFailure.Kind.TRANSPORT, TileFailure.Kind.STATUS),
                result.tileFailures.map { it.kind },
            )
            val haystack = result.tileFailures.joinToString("\n") { f ->
                listOf(f.toString(), f.error.toString(), f.error.message.orEmpty(), f.error.stackTraceToString()).joinToString("\n")
            }.lowercase()
            for (needle in listOf(text.lowercase(), text.trim().lowercase(), "zanzibar quokka", "zanzib", "quokka")) {
                assertFalse(needle in haystack, "\"$needle\" in tileFailures")
            }
        }

        @Test
        fun `sends no body`() = runTest {
            val fake = server()
            client(fake).searchPlaces(Region.Bbox(-122.43, 37.77, -122.41, 37.78), OVERTURE_DATASET, text = "Zanzibar Quokka", collection = OVERTURE_COLLECTION)
            assertTrue(fake.requests.isNotEmpty())
            // HttpTransport.get(url) carries no body.
        }
    }

    @Nested
    @DisplayName("precise coordinates stay local")
    inner class PreciseCoordinates {
        @Test
        fun `keeps the circle centre off the wire`() = runTest {
            val fake = server()
            client(fake).searchPlaces(Region.Circle(37.7749295, -122.4194155, 123.456789), OVERTURE_DATASET, text = "Zanzibar Quokka", collection = OVERTURE_COLLECTION)
            assertTrue(fake.requests.isNotEmpty())
            for (h in wire(fake.requests)) {
                for (needle in listOf("37.7749295", "122.4194155", "7749295", "4194155", "123.456789")) assertFalse(needle in h, h)
            }
        }

        @Test
        fun `keeps full-precision bbox corners off the wire`() = runTest {
            val fake = server()
            client(fake).searchPlaces(Region.Bbox(-122.4194155, 37.7749295, -122.4094155, 37.7849295), OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
            assertTrue(fake.requests.isNotEmpty())
            for (h in wire(fake.requests)) {
                for (needle in listOf("7749295", "4194155", "4094155", "7849295")) assertFalse(needle in h, h)
            }
        }
    }
}
