package org.atgeo.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimilarityTest {
    private data class Case(val a: String, val b: String, val score: Double)

    private val cases = (fixture("jaro-winkler.json") as JsonArray).map {
        val obj = it as JsonObject
        Case(obj.getValue("a").jsonPrimitive.content, obj.getValue("b").jsonPrimitive.content, obj.getValue("score").jsonPrimitive.double)
    }

    @Nested
    @DisplayName("jaroWinkler")
    inner class JaroWinkler {
        // vitest's toBeCloseTo(score, 6): |actual - expected| < 5e-7.
        @TestFactory
        fun scores(): List<DynamicTest> = cases.map { (a, b, score) ->
            dynamicTest("scores $a / $b") {
                val actual = jaroWinkler(a, b)
                assertTrue(abs(actual - score) < 5e-7, "jaroWinkler($a, $b) = $actual, expected $score")
            }
        }

        @TestFactory
        fun symmetric(): List<DynamicTest> = cases.map { (a, b) ->
            dynamicTest("is symmetric for $a / $b") { assertEquals(jaroWinkler(a, b), jaroWinkler(b, a)) }
        }
    }

    // Values from the reference.
    @Nested
    @DisplayName("contract examples (no reference counterpart)")
    inner class ContractExamples {
        @Test
        fun `compares UTF-16 code units, not code points`() {
            assertEquals(0.8833333333333334, jaroWinkler("😀😁", "😀😂"))
        }

        @Test
        fun `runs the inner loop zero times when its start passes its end`() {
            assertEquals(0.6507936507936508, jaroWinkler("starbux", "bar"))
            assertEquals(0.6507936507936508, jaroWinkler("bar", "starbux"))
        }
    }
}
