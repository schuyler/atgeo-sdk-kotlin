package org.atgeo.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val region = Region.Bbox(-122.42, 37.77, -122.41, 37.78)
private val inside = point(37.775, -122.415)

// Stack-trace recovery (on under -ea) may rethrow a copy whose cause is the original, so look down the chain.
private fun causes(e: Throwable) = generateSequence(e) { it.cause }

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
        val result = AtgeoClient(transport = transport, computeDispatcher = Dispatchers.Default).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
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
        val search = async(Dispatchers.Default) { client.searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION) }
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
        val result = client(transport).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION)
        assertEquals(SearchResult.TileCounts(fetched = 1, failed = 8), result.tiles)
        assertEquals(false, result.complete)
        assertEquals(listOf(placeOrigin("p8")), result.places.map { it.origin })
    }

    // Outside the tile loop the same exception would reach the caller as a CancellationException
    // while the caller is still active, which ends a launch {} silently. It is a network failure.
    @Test
    fun `reports a transport timeout on an XRPC call as SocketTimeoutException`() = runTest {
        val c = client(HttpTransport { withTimeout(10) { awaitCancellation() } })
        val calls: List<suspend () -> Unit> = listOf(
            { c.searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION) },
            { c.getPlace(placeOrigin("p0")) },
            { c.describeService() },
            { c.describeTileset("overture-place") },
        )
        for (call in calls) {
            val error = assertFailsWith<SocketTimeoutException> { call() }
            assertTrue(causes(error).any { it is TimeoutCancellationException })
        }
    }

    @Test
    fun `reports any other transport CancellationException as IOException`() = runTest {
        val error = assertFailsWith<IOException> { client(HttpTransport { throw CancellationException("gave up") }).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION) }
        assertTrue(causes(error).any { it is CancellationException })
    }

    @Test
    fun `still propagates the caller's cancellation from getTiles`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val client = AtgeoClient(transport = { started.complete(Unit); awaitCancellation() }, computeDispatcher = Dispatchers.Default)
        val search = async(Dispatchers.Default) { client.searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION) }
        withTimeout(5_000) { started.await() }
        search.cancel()
        assertIs<CancellationException>(runCatching { search.await() }.exceptionOrNull())
        assertTrue(search.isCancelled)
    }

    @Test
    fun `lets an Error from a tile fetch fail the search rather than count as a failed tile`() = runTest {
        val server = manyTiles(2)
        val transport = HttpTransport { url -> if (url == tileUrl("t01")) throw OutOfMemoryError("probe") else server.get(url) }
        assertFailsWith<OutOfMemoryError> { client(transport).searchPlaces(region, OVERTURE_DATASET, collection = OVERTURE_COLLECTION) }
    }
}
