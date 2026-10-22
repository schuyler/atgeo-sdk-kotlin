package org.atgeo.client

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode

// Builders mirroring test/helpers/fakeServer.ts.

internal const val DEFAULT_SERVER = "https://places.atgeo.org"

internal fun placeOrigin(id: String) = "urn:atgeo:overturemaps.org:place:$id"

// JavaScript's toFixed(6): the exact binary value, rounded half away from zero.
private fun toFixed6(x: Double): String = BigDecimal(x).setScale(6, RoundingMode.HALF_UP).toPlainString()

internal fun point(lat: Double, lon: Double): JsonObject = buildJsonObject {
    put("\$type", "community.lexicon.location.geo")
    put("latitude", toFixed6(lat))
    put("longitude", toFixed6(lon))
}

internal fun bboxLocation(north: Double, south: Double, east: Double, west: Double): JsonObject = buildJsonObject {
    put("\$type", "community.lexicon.location.bbox")
    put("north", toFixed6(north))
    put("south", toFixed6(south))
    put("east", toFixed6(east))
    put("west", toFixed6(west))
}

/** `extra` may override `origin`, or map it to null to make a value with no origin. */
internal fun place(
    id: String,
    name: String,
    locations: List<JsonElement>,
    extra: Map<String, JsonElement?> = emptyMap(),
): JsonObject {
    val fields = linkedMapOf<String, JsonElement>(
        "\$type" to JsonPrimitive("org.atgeo.place"),
        "origin" to JsonPrimitive(placeOrigin(id)),
        "name" to JsonPrimitive(name),
        "locations" to kotlinx.serialization.json.JsonArray(locations),
    )
    for ((key, value) in extra) if (value == null) fields.remove(key) else fields[key] = value
    return JsonObject(fields)
}

/** A tile record as served: `{ value, importance?, uri? }`. */
internal fun record(value: JsonObject, importance: Long? = null, uri: String? = null): JsonObject = buildJsonObject {
    put("value", value)
    if (importance != null) put("importance", importance)
    if (uri != null) put("uri", uri)
}
