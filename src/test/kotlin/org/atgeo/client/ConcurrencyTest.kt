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
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val region = Region.Bbox(-122.42, 37.77, -122.41, 37.78)
private val inside = point(37.775, -122.415)

private fun manyTiles(n: Int) = FakeServer(
    tilesets = listOf(overture((0 until n).map { i -> FakeTile(tileUrl("t%02d".format(i)), listOf(record(place("p$i", "P$i", listOf(inside))))) })),
)

/** The search's concurrency (no reference counterpart: the reference has no tests for the cap or cancellation). */
class ConcurrencyTest {
    // On real threads: the ported suites run single-threaded on the test scheduler, where the cap is never reached.
    @Test
    fun `never has more than 8 tile requests in flight, and reaches 8`() = runBlocking {
        val server = manyTiles(30)
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val transport = HttpTransport { url ->
            if ("/xrpc/" in url) return@HttpTransport server.get(url)
            val now = inFlight.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                delay(30)
                server.get(url)
            } finally {
                inFlight.decrementAndGet()
            }
        }
        val result = AtgeoClient(transport = transport, computeDispatcher = Dispatchers.Default).searchPlaces(region)
        assertEquals(SearchResult.TileCounts(fetched = 30, failed = 0), result.tiles)
        assertEquals(30, result.places.size)
        assertEquals(8, peak.get())
    }

    @Test
    fun `stops promptly when the caller is cancelled, starting no further requests`() = runBlocking {
        val server = manyTiles(30)
        val tileRequests = AtomicInteger()
        val eightStarted = CompletableDeferred<Unit>()
        val transport = HttpTransport { url ->
            if ("/xrpc/" in url) return@HttpTransport server.get(url)
            if (tileRequests.incrementAndGet() == 8) eightStarted.complete(Unit)
            awaitCancellation()
        }
        val client = AtgeoClient(transport = transport, computeDispatcher = Dispatchers.Default)
        val search = async(Dispatchers.Default) { client.searchPlaces(region) }
        withTimeout(5_000) { eightStarted.await() }
        val started = System.nanoTime()
        search.cancel()
        val error = runCatching { search.await() }.exceptionOrNull()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertIs<CancellationException>(error)
        assertTrue(elapsedMillis < 500, "took $elapsedMillis ms")
        delay(100)
        assertEquals(8, tileRequests.get())
    }

    // A transport's own withTimeout throws TimeoutCancellationException, which is a
    // CancellationException. Rethrowing it from the worker would end that worker quietly (a child
    // that completes with a CancellationException does not fail its parent), so with every worker
    // timing out at once the remaining tiles would never be fetched. Eight timeouts and a ninth tile
    // tell the two rules apart.
    @Test
    fun `counts a tile that times out inside the transport as failed, and keeps fetching`() = runTest {
        val server = manyTiles(9)
        val transport = HttpTransport { url ->
            if (url != tileUrl("t08") && "/xrpc/" !in url) withTimeout(10) { awaitCancellation() } else server.get(url)
        }
        val result = client(transport).searchPlaces(region)
        assertEquals(SearchResult.TileCounts(fetched = 1, failed = 8), result.tiles)
        assertEquals(false, result.complete)
        assertEquals(listOf(placeOrigin("p8")), result.places.map { it.origin })
    }
}
