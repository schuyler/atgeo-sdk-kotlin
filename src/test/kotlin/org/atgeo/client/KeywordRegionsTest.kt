package org.atgeo.client

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * keyword-regions.json: the whole-degree bbox a keyword search sends for each input box. The fake refuses what
 * the real server refuses, so a case passes only if the server accepts what was sent (contract §16).
 */
class KeywordRegionsTest {
    private val plainIntegers = Regex("""^(0|-?[1-9]\d*)(,(0|-?[1-9]\d*)){3}$""")

    private suspend fun kotlinx.coroutines.test.TestScope.run(r: KeywordRegion): List<String> {
        val fake = FakeServer(tilesets = listOf(overture(emptyList())))
        client(fake).searchByKeyword("Zanzibar", OVERTURE_DATASET, region = r)
        return fake.requests
    }

    @TestFactory
    fun cases(): List<DynamicTest> = (fixture("keyword-regions.json") as JsonArray).map { element ->
        val c = element as JsonObject
        val b = (c.getValue("bbox") as JsonArray).map { it.jsonPrimitive.content.toDouble() }
        val sent = c.getValue("sent").jsonPrimitive.content
        dynamicTest(c.getValue("name").jsonPrimitive.content) {
            runTest {
                val requests = run(KeywordRegion(b[0], b[1], b[2], b[3]))
                assertEquals(1, requests.size)
                assertEquals(sent, paramsOf(requests[0])["bbox"])
                assertTrue(plainIntegers.matches(sent), sent)
            }
        }
    }

    @Test
    fun `keeps every digit of a precise box off the wire`() = runTest {
        val requests = run(KeywordRegion(-122.4194155, 37.7749295, -122.4094155, 37.7849295))
        assertEquals("-123,37,-122,38", paramsOf(requests[0])["bbox"])
        for (needle in listOf("7749295", "4194155", "4094155", "7849295")) assertFalse(needle in requests[0])
    }

    // floor and ceil on negatives, and no -0: a box just below zero and one that straddles it.
    @Test
    fun `snaps negative values outward and never writes -0`() {
        assertEquals("-1,-1,0,0", keywordBbox(-0.5, -0.5, -0.0, -0.0))
        assertEquals("-1,-1,1,1", keywordBbox(-0.5, -0.5, 0.5, 0.5))
        assertEquals("0,0,1,1", keywordBbox(-0.0, 0.0, 0.0, 0.0))
    }
}
