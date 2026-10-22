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
}
