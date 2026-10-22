package org.atgeo.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The vendored fixtures and lexicons load from the test classpath. */
class FixturesTest {
    @Test
    fun `shared fixtures parse and have the expected case counts`() {
        assertEquals(36, (fixture("normalize.json") as JsonArray).size)
        assertEquals(33, (fixture("jaro-winkler.json") as JsonArray).size)
        assertEquals(12, (fixture("ranking-scenarios.json") as JsonArray).size)
    }

    @Test
    fun `lexicons parse`() {
        val names = listOf(
            "address", "bbox", "defs", "describeService", "describeTileset",
            "geo", "getPlace", "getTiles", "place", "tilePayload",
        )
        for (name in names) {
            val lexicon = fixture("lexicon/$name.json") as JsonObject
            assertTrue("id" in lexicon, "lexicon/$name.json has no id")
        }
    }

    @Test
    fun `each vendored tree names its source`() {
        assertTrue(fixtureText("SOURCE").startsWith("atgeo-sdk-js "))
        assertTrue(fixtureText("lexicon/SOURCE").startsWith("garganorn "))
    }
}
