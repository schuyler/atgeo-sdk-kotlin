package org.atgeo.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val CAP = 100_000

/** The default transport against a loopback server (no reference counterpart: the fake replaces it everywhere else). */
class HttpUrlConnectionTransportTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val transport = HttpUrlConnectionTransport()
    private val firstChunkSent = CountDownLatch(1)
    private val releaseSlowBody = CountDownLatch(1)
    private val seenHeaders = CopyOnWriteArrayList<Map<String, List<String>>>()

    private fun HttpExchange.respond(status: Int, body: String?) {
        if (body == null) {
            sendResponseHeaders(status, -1)
        } else {
            val bytes = body.toByteArray()
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
        close()
    }

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        server.createContext("/error-json") { it.respond(400, """{"error":"InvalidRequest","message":"bad bbox"}""") }
        server.createContext("/error-empty") { it.respond(404, null) }
        server.createContext("/ok") { it.respond(200, """{"ok":true}""") }
        server.createContext("/redirect") {
            it.responseHeaders.add("Location", "/ok")
            it.respond(302, null)
        }
        server.createContext("/slow") { exchange ->
            exchange.sendResponseHeaders(200, 0) // chunked
            exchange.responseBody.write("{\"records\":[".toByteArray())
            exchange.responseBody.flush()
            firstChunkSent.countDown()
            releaseSlowBody.await(10, TimeUnit.SECONDS)
            runCatching { exchange.responseBody.use { it.write("]}".toByteArray()) } }
            exchange.close()
        }
        // Gzips only when asked, as places.atgeo.org does.
        server.createContext("/negotiated") { exchange ->
            val body = """{"records":[]}""".toByteArray()
            if (exchange.requestHeaders.getFirst("Accept-Encoding").orEmpty().contains("gzip")) {
                val packed = java.io.ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(body) } }.toByteArray()
                exchange.responseHeaders.add("Content-Encoding", "gzip")
                exchange.sendResponseHeaders(200, packed.size.toLong())
                exchange.responseBody.use { it.write(packed) }
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.createContext("/gzip-empty") {
            it.responseHeaders.add("Content-Encoding", "gzip")
            it.respond(404, null)
        }
        server.createContext("/bytes/") { exchange ->
            val size = exchange.requestURI.path.substringAfterLast('/').toInt()
            val body = ByteArray(size) { 'x'.code.toByte() }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        // A few kilobytes on the wire, CAP * 64 once decoded.
        server.createContext("/gzip-bomb") { exchange ->
            val packed = java.io.ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(ByteArray(CAP * 64)) } }.toByteArray()
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, packed.size.toLong())
            exchange.responseBody.use { it.write(packed) }
            exchange.close()
        }
        server.createContext("/headers") { exchange ->
            seenHeaders.add(exchange.requestHeaders.mapKeys { it.key.lowercase() })
            exchange.respond(200, "{}")
        }
        server.start()
        base = "http://${server.address.hostString}:${server.address.port}"
    }

    @AfterEach
    fun stop() {
        releaseSlowBody.countDown()
        server.stop(0)
    }

    @Test
    fun `returns a 4xx with a JSON error body as a response, not an exception`() = runBlocking {
        val response = transport.get("$base/error-json")
        assertEquals(400, response.status)
        assertContentEquals("""{"error":"InvalidRequest","message":"bad bbox"}""".toByteArray(), response.body)
    }

    @Test
    fun `reads an empty error body as empty`() = runBlocking {
        val response = transport.get("$base/error-empty")
        assertEquals(404, response.status)
        assertContentEquals(ByteArray(0), response.body)
    }

    @Test
    fun `follows a same-scheme redirect`() = runBlocking {
        val response = transport.get("$base/redirect")
        assertEquals(200, response.status)
        assertContentEquals("""{"ok":true}""".toByteArray(), response.body)
    }

    @Test
    fun `asks for gzip and decodes a gzipped body`() = runBlocking {
        val response = transport.get("$base/negotiated")
        assertEquals(200, response.status)
        assertContentEquals("""{"records":[]}""".toByteArray(), response.body)
    }

    @Test
    fun `reads an empty body labelled gzip as empty`() = runBlocking {
        val response = transport.get("$base/gzip-empty")
        assertEquals(404, response.status)
        assertContentEquals(ByteArray(0), response.body)
    }

    @Test
    fun `reads a body of exactly maxBodyBytes`() = runBlocking {
        assertEquals(CAP, HttpUrlConnectionTransport(maxBodyBytes = CAP).get("$base/bytes/$CAP").body.size)
    }

    @Test
    fun `fails with IOException for a body over maxBodyBytes`() = runBlocking<Unit> {
        assertFailsWith<IOException> { HttpUrlConnectionTransport(maxBodyBytes = CAP).get("$base/bytes/${CAP + 1}") }
    }

    @Test
    fun `fails with IOException for a small gzip body that decodes over maxBodyBytes`() = runBlocking<Unit> {
        assertFailsWith<IOException> { HttpUrlConnectionTransport(maxBodyBytes = CAP).get("$base/gzip-bomb") }
    }

    @Test
    fun `rejects a maxBodyBytes that is not positive`() {
        assertFailsWith<IllegalArgumentException> { HttpUrlConnectionTransport(maxBodyBytes = 0) }
    }

    // On the JDK, disconnect() does not interrupt a body read already in progress, so this asserts
    // that the caller returns promptly, not that the socket closes (spec §4).
    @Test
    fun `returns promptly with CancellationException when cancelled mid-body`() = runBlocking {
        val call = async(Dispatchers.Default) { transport.get("$base/slow") }
        withContext(Dispatchers.IO) { assertTrue(firstChunkSent.await(5, TimeUnit.SECONDS), "server never sent the first chunk") }
        Thread.sleep(200) // let the client get into the body read
        val started = System.nanoTime()
        call.cancel()
        val error = runCatching { call.await() }.exceptionOrNull()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertIs<CancellationException>(error)
        assertTrue(elapsedMillis < 500, "took $elapsedMillis ms to return")
    }

    // Privacy (contract §12): the transport adds only fixed headers of its own, the same on every request.
    // useCaches = false makes the JDK add Pragma and Cache-Control (both "no-cache"); Accept-Encoding is always gzip,
    // and User-Agent always the library's own.
    @Test
    fun `sends a fixed set of headers, Accept application-json among them, identical across requests`() = runBlocking {
        transport.get("$base/headers/a?bbox=1.00,2.00,3.00,4.00")
        transport.get("$base/headers/b/c.json")
        assertEquals(2, seenHeaders.size)
        val (first, second) = seenHeaders
        assertEquals(first, second)
        assertEquals(listOf("application/json"), first["accept"])
        assertEquals(listOf("gzip"), first["accept-encoding"])
        // Not the platform's: Android's names the device model.
        assertEquals(listOf("atgeo-client-kotlin"), first["user-agent"])
        val allowed = setOf("accept", "accept-encoding", "user-agent", "host", "connection", "pragma", "cache-control")
        assertTrue(allowed.containsAll(first.keys), "unexpected request headers: ${first.keys - allowed}")
    }
}
