package org.atgeo.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/** match-tiers.json: a query, a place built from `place.name` and `place.variants`, and the tier (contract §7). */
class MatchTiersTest {
    @TestFactory
    fun cases(): List<DynamicTest> = (fixture("match-tiers.json") as JsonArray).map { element ->
        val c = element as JsonObject
        dynamicTest(c.getValue("name").jsonPrimitive.content) {
            val place = c.getValue("place") as JsonObject
            val value = JsonObject(
                buildMap {
                    put("name", place.getValue("name"))
                    place["variants"]?.let { put("variants", it) }
                },
            )
            assertEquals(c.getValue("tier").jsonPrimitive.int, matchTier(normalize(c.getValue("query").jsonPrimitive.content), value))
        }
    }
}
