package org.atgeo.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** qualifying-keywords.json: the predicate of contract §17 on the raw keyword. */
class QualifyingTest {
    @TestFactory
    fun cases(): List<DynamicTest> = (fixture("qualifying-keywords.json") as JsonArray).map { element ->
        val c = element as JsonObject
        val keyword = c.getValue("keyword").jsonPrimitive.content
        dynamicTest("${c.getValue("name").jsonPrimitive.content}: $keyword") {
            assertEquals(c.getValue("qualifies").jsonPrimitive.boolean, qualifies(keyword))
        }
    }

    // Code points, not UTF-16 units: one astral character is two units and one code point.
    @Test
    fun `counts code points, not UTF-16 units`() {
        assertEquals(false, qualifies("😀"))
        assertEquals(true, qualifies("😀😁"))
    }
}
