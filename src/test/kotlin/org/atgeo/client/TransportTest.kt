package org.atgeo.client

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransportTest {
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
