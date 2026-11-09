package org.atgeo.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@DisplayName("autocompleteByKeyword")
class AutocompleteTest {
    private val tiles = listOf(FakeTile("https://tiles.example/p.json", listOf(record(place("zc", "Zanzibar Cafe", listOf(point(37.775, -122.418))), 5))))
    private val nothing = KeywordSearchResult(emptyList(), emptyList(), Disclosure(false, null))

    private fun TestScope.setup(): Pair<FakeServer, AtgeoClient> = FakeServer(tilesets = listOf(overture(tiles))).let { it to client(it) }

    // A prefix of one character, a Hangul syllable (one character, though NFKD makes it two), and a consonant
    // with a spacing vowel sign (one character once the sign is stripped).
    @ParameterizedTest
    @ValueSource(strings = ["a", "하", "का"])
    fun `makes no request for a keyword that cannot qualify and resolves an empty result that disclosed nothing`(prefix: String) = runTest {
        val (fake, client) = setup()
        assertEquals(nothing, client.autocompleteByKeyword(OVERTURE_DATASET).searchByKeyword(prefix))
        assertEquals(0, fake.requests.size)
    }

    @Test
    fun `sends a prefix that qualifies, as typed, and returns the keyword hits`() = runTest {
        val (fake, client) = setup()
        val result = client.autocompleteByKeyword(OVERTURE_DATASET, OVERTURE_COLLECTION).searchByKeyword("Zan")
        assertEquals(mapOf("dataset" to OVERTURE_DATASET, "collection" to OVERTURE_COLLECTION, "q" to "Zan"), paramsOf(fake.requests.single()))
        assertEquals(listOf(placeOrigin("zc")), result.places.map { it.origin })
    }

    @Test
    fun `sends the autocompleter's region snapped to whole degrees`() = runTest {
        val (fake, client) = setup()
        client.autocompleteByKeyword(OVERTURE_DATASET, region = KeywordRegion(-122.42, 37.77, -122.41, 37.78)).searchByKeyword("Zan")
        assertEquals("-123,37,-122,38", paramsOf(fake.requests.single())["bbox"])
    }

    @Test
    fun `refuses a bad dataset region at construction`() = runTest {
        val (_, client) = setup()
        assertFailsWith<IllegalArgumentException> { client.autocompleteByKeyword(OVERTURE_DATASET, region = KeywordRegion(0.0, 10.0, 1.0, 5.0)) }
    }

    // A transport whose first request waits to be cancelled; the rest go to the fake.
    private class FirstHangs(private val fake: FakeServer, val started: CompletableDeferred<Unit> = CompletableDeferred()) : HttpTransport {
        private var calls = 0
        override suspend fun get(url: String): HttpResponse {
            if (++calls > 1) return fake.get(url)
            started.complete(Unit)
            awaitCancellation()
        }
    }

    @Test
    fun `supersedes the previous call on the same autocompleter, which throws CancellationException`() = runTest {
        val transport = FirstHangs(FakeServer(tilesets = listOf(overture(tiles))))
        val autocompleter = client(transport).autocompleteByKeyword(OVERTURE_DATASET)
        val first = async { runCatching { autocompleter.searchByKeyword("Za") }.exceptionOrNull() }
        transport.started.await()
        val second = autocompleter.searchByKeyword("Zan")
        assertTrue(first.await() is CancellationException)
        assertEquals(1, second.places.size)
    }

    @Test
    fun `an unqualified keyword still supersedes the previous call`() = runTest {
        val transport = FirstHangs(FakeServer(tilesets = listOf(overture(tiles))))
        val autocompleter = client(transport).autocompleteByKeyword(OVERTURE_DATASET)
        val first = async { runCatching { autocompleter.searchByKeyword("Za") }.exceptionOrNull() }
        transport.started.await()
        assertEquals(nothing, autocompleter.searchByKeyword("z"))
        assertTrue(first.await() is CancellationException)
    }

    @Test
    fun `does not supersede a call on another autocompleter of the same client`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeServer(tilesets = listOf(overture(tiles)))
        var calls = 0
        val client = client(HttpTransport { url -> if (++calls == 1) gate.await(); fake.get(url) })
        val first = async { client.autocompleteByKeyword(OVERTURE_DATASET).searchByKeyword("Za") }
        runCurrent()
        val second = client.autocompleteByKeyword(OVERTURE_DATASET).searchByKeyword("Zan")
        gate.complete(Unit)
        assertTrue(first.await().disclosed.keyword)
        assertEquals(1, second.places.size)
    }

    @Nested
    @DisplayName("when the server refuses a keyword the SDK judged qualifying")
    inner class Refused {
        @Test
        fun `resolves empty for a ligature, disclosing the keyword it sent`() = runTest {
            val (fake, client) = setup()
            val result = client.autocompleteByKeyword(OVERTURE_DATASET).searchByKeyword("ﬁ")
            assertEquals(1, fake.requests.size)
            assertEquals(KeywordSearchResult(emptyList(), emptyList(), Disclosure(true, null)), result)
        }

        @Test
        fun `reports the region it sent with the refused keyword`() = runTest {
            val (_, client) = setup()
            val result = client.autocompleteByKeyword(OVERTURE_DATASET, region = KeywordRegion(-122.42, 37.77, -122.41, 37.78)).searchByKeyword("ﬁ")
            assertEquals(Disclosure(true, 1.0), result.disclosed)
        }

        @Test
        fun `any other refusal still fails the call`() = runTest {
            val client = client(HttpTransport { HttpResponse(400, """{"error":"InvalidRequest","message":"q is too long"}""".toByteArray()) })
            assertEquals("InvalidRequest", assertFailsWith<AtgeoException> { client.autocompleteByKeyword("d").searchByKeyword("Zan") }.code)
        }
    }

    @Nested
    @DisplayName("cancellation")
    inner class Cancellation {
        @Test
        fun `a call from an already-cancelled caller sends nothing`() = runTest {
            val (fake, client) = setup()
            val call = async(start = kotlinx.coroutines.CoroutineStart.LAZY) { client.autocompleteByKeyword(OVERTURE_DATASET).searchByKeyword("Zan") }
            call.cancel(CancellationException("caller gave up"))
            assertFailsWith<CancellationException> { call.await() }
            assertEquals(0, fake.requests.size)
        }

        @Test
        fun `the caller's own cancellation mid-flight comes back as the caller's reason`() = runTest {
            val transport = FirstHangs(FakeServer(tilesets = listOf(overture(tiles))))
            val call = async { client(transport).autocompleteByKeyword(OVERTURE_DATASET).searchByKeyword("Zan") }
            transport.started.await()
            call.cancel(CancellationException("caller gave up"))
            val e = assertFailsWith<CancellationException> { call.await() }
            assertEquals("caller gave up", generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.first { it == "caller gave up" })
        }
    }
}
