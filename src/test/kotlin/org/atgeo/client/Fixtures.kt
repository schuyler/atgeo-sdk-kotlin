package org.atgeo.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Reads a file under src/test/resources/fixtures from the test classpath. */
internal fun fixtureText(path: String): String {
    val stream = Fixtures::class.java.getResourceAsStream("/fixtures/$path")
        ?: error("fixture not on the test classpath: $path")
    return stream.use { it.readBytes().decodeToString() }
}

internal fun fixture(path: String): JsonElement = Json.parseToJsonElement(fixtureText(path))

private object Fixtures
