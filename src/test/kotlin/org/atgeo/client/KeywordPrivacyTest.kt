package org.atgeo.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val tiles = listOf(FakeTile("https://tiles.example/p.json", listOf(record(place("zc", "Zanzibar Cafe", listOf(point(37.772, -122.412))), 5))))
private val nearbyRegion = Region.Circle(37.7749295, -122.4194155, 500.0)

// Contract §12 items 6 to 9, and §11: a request whose cancellation already happened never reaches the transport.
@DisplayName("keyword privacy and cancelled requests")
class KeywordPrivacyTest {
    @Test
    fun `item 7 - the fallback's nearby half keeps the keyword local, and it is sent in searchPlaces only`() = runTest {
        val fake = FakeServer(tilesets = listOf(overture(tiles)))
        val result = client(fake).searchNearbyThenByKeyword(nearbyRegion, OVERTURE_DATASET, "Kumquat Lagoon", KeywordRegionMode.NONE)
        val search = fake.requests.filter { "org.atgeo.searchPlaces" in it }
        val nearby = fake.requests - search.toSet()
        assertEquals(1, search.size)
        assertTrue(nearby.isNotEmpty())
        assertEquals(mapOf("dataset" to OVERTURE_DATASET, "q" to "Kumquat Lagoon"), paramsOf(search[0]))
        for (haystack in nearby.map { it.lowercase() }) for (needle in listOf("kumquat lagoon", "kumquat", "lagoon", "kumqu")) assertFalse(needle in haystack)
        assertEquals(Disclosure(true, 0.01), result.disclosed)
    }

    @Test
    fun `item 7 - a tier-1 nearby hit or an unqualified keyword sends no keyword in any form`() = runTest {
        for (keyword in listOf("Zanzibar", "q")) {
            val fake = FakeServer(tilesets = listOf(overture(tiles)))
            val result = client(fake).searchNearbyThenByKeyword(Region.Circle(37.7749295, -122.4194155, 2000.0), OVERTURE_DATASET, keyword, KeywordRegionMode.DEGREE)
            assertTrue(fake.requests.none { "searchPlaces" in it })
            if (keyword.length > 1) for (r in fake.requests) assertFalse(keyword.lowercase() in r.lowercase())
            assertEquals(Disclosure(false, 0.01), result.disclosed)
        }
    }

    @Test
    fun `item 6 - a precise circle's digits never appear in a degree fallback request`() = runTest {
        val fake = FakeServer(tilesets = listOf(overture(tiles)))
        client(fake).searchNearbyThenByKeyword(Region.Bbox(-122.4194155, 37.7749295, -122.4094155, 37.7849295), OVERTURE_DATASET, "Kumquat", KeywordRegionMode.DEGREE)
        val search = fake.requests.single { "searchPlaces" in it }
        assertTrue(Regex("""^-?\d+,-?\d+,-?\d+,-?\d+$""").matches(paramsOf(search).getValue("bbox")))
        for (r in fake.requests) for (needle in listOf("7749295", "4194155", "4094155", "7849295")) assertFalse(needle in r)
    }

    @Test
    fun `item 8 - every prefix that qualifies goes out exactly as searchByKeyword sends it`() = runTest {
        val fake = FakeServer(tilesets = listOf(overture(tiles)))
        val ac = client(fake).autocompleteByKeyword(OVERTURE_DATASET, region = KeywordRegion(-122.42, 37.77, -122.41, 37.78))
        for (prefix in listOf("Z", "Za", "Zan", "Zanz")) ac.searchByKeyword(prefix)
        assertEquals(listOf("Za", "Zan", "Zanz"), fake.requests.map { paramsOf(it).getValue("q") })
        assertTrue(fake.requests.all { paramsOf(it)["bbox"] == "-123,37,-122,38" })
    }

    // An app's transport that ignores cancellation: it would send whatever it is handed.
    private class Deaf(val fake: FakeServer) : HttpTransport {
        override suspend fun get(url: String): HttpResponse = fake.get(url)
    }

    @Test
    fun `a request made after the caller cancelled never reaches the transport`() = runTest {
        val fake = FakeServer(tilesets = listOf(overture(tiles)))
        val client = client(Deaf(fake))
        val tilesAndLists = async {
            // Cancel from inside, then make one call of each kind that touches the transport.
            kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!.cancel(CancellationException("caller gave up"))
            for (call in listOf<suspend () -> Unit>(
                { client.describeService() },
                { client.describeTileset("overture-place") },
                { client.getPlace(placeOrigin("zc")) },
            )) {
                assertFailsWith<CancellationException> { call() }
            }
        }
        assertFailsWith<CancellationException> { tilesAndLists.await() }
        assertEquals(0, fake.requests.size)
    }

    @Test
    fun `the keyword search never sends for a cancelled caller`() = runTest {
        val fake = FakeServer(tilesets = listOf(overture(tiles)))
        val client = client(Deaf(fake))
        var outcome: Throwable? = null
        val call = async {
            kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!.cancel(CancellationException("caller gave up"))
            // An exception thrown after the cancel would be lost with the coroutine, so record it.
            outcome = runCatching { client.searchByKeyword("Quokka", OVERTURE_DATASET) }.exceptionOrNull()
        }
        call.join()
        assertTrue(outcome is CancellationException, "$outcome")
        assertEquals(0, fake.requests.size)
    }

    @Test
    fun `the fallback's keyword half never sends for a caller that cancelled after the nearby half`() = runTest {
        val fake = FakeServer(tilesets = listOf(overture(tiles)))
        val client = client(Deaf(fake))
        // Warm the cache, so the nearby half needs no request; the keyword half is then the first one made.
        client.searchNearby(nearbyRegion, dataset = OVERTURE_DATASET)
        val before = fake.requests.size
        var outcome: Throwable? = null
        val call = async {
            kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!.cancel(CancellationException("caller gave up"))
            outcome = runCatching { client.searchNearbyThenByKeyword(nearbyRegion, OVERTURE_DATASET, "Quokka", KeywordRegionMode.NONE) }.exceptionOrNull()
        }
        call.join()
        assertTrue(outcome is CancellationException, "$outcome")
        assertEquals(before, fake.requests.size)
        assertTrue(fake.requests.none { "searchPlaces" in it })
    }
}
