package org.atgeo.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

class NormalizeTest {
    @Nested
    @DisplayName("normalize")
    inner class Normalize {
        @TestFactory
        fun `normalizes the fixture`(): List<DynamicTest> =
            (fixture("normalize.json") as JsonArray).map { case ->
                val obj = case as JsonObject
                val input = obj.getValue("input").jsonPrimitive.content
                val expected = obj.getValue("expected").jsonPrimitive.content
                dynamicTest("normalizes $input") { assertEquals(expected, normalize(input)) }
            }
    }

    // Contract §7 names the first three and asks each port to test them itself until the fixture does.
    // The other three are the Greek final-sigma cases the Swift port tests; values from the reference.
    @Nested
    @DisplayName("case mapping (no reference counterpart)")
    inner class CaseMapping {
        @TestFactory
        fun `lowercases like V8`(): List<DynamicTest> = listOf(
            "ΟΔΟΣ" to "οδος",
            "İstanbul" to "istanbul",
            "ẞ" to "ß",
            "Ὀδυσσεύς" to "οδυσσευς",
            "Α.Σ." to "α.ς.",
            "ΑΣ.Α" to "ασ.α",
        ).map { (input, expected) ->
            dynamicTest("normalizes $input") { assertEquals(expected, normalize(input)) }
        }
    }
}
