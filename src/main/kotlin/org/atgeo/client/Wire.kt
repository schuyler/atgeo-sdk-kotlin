package org.atgeo.client

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs

// Lenient readers from parsed JSON (contract §1). A missing or
// wrong-typed field reads as absent; only a body with nothing usable throws.
// A primitive is reached with `as? JsonPrimitive`, never `.jsonPrimitive`
// (which throws on an object or array), and its kind is checked before it is
// read: longOrNull and doubleOrNull parse string content, so "5" would read as 5.

/**
 * The string a JSON string holds; null for anything else, a number included. Reads a field of a place
 * without a cast: `hit.place["name"].stringOrNull()`. (kotlinx's `contentOrNull` would turn 5 into "5".)
 */
public fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The value of a JSON number; null for a string, boolean, null, object or array. */
internal fun JsonElement?.numberOrNull(): Double? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

// 2^63 exactly. The check is symmetric, so -2^63 is absent too (contract §1).
private const val TWO_TO_THE_63 = 9.223372036854775807E18

/**
 * A JSON number read through a double, as the reference does, truncated toward zero; absent when
 * not a finite number below 2^63 in magnitude. Double.toLong() alone would saturate instead.
 */
internal fun JsonElement?.int64OrNull(): Long? =
    numberOrNull()?.takeIf { it.isFinite() && abs(it) < TWO_TO_THE_63 }?.toLong()

/** JavaScript truthiness: false for absent, null, false, 0, -0, NaN and "", true for anything else. */
internal fun JsonElement?.truthy(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonPrimitive -> when {
        isString -> content.isNotEmpty()
        // A non-string primitive is a boolean, a number, or an unquoted literal the tree parser let through.
        else -> booleanOrNull ?: doubleOrNull?.let { !(it == 0.0 || it.isNaN()) } ?: true
    }
    else -> true
}

/**
 * Parses a response body. Malformed UTF-8 becomes U+FFFD, as with fetch's text(). Throws
 * AtgeoDecodingException, with the parser's SerializationException as its cause, when it is not JSON.
 */
internal fun parseBody(body: ByteArray): JsonElement = try {
    Json.parseToJsonElement(body.decodeToString())
} catch (e: SerializationException) {
    throw AtgeoDecodingException("response body is not JSON: ${e.message}", e)
}

private fun unusable(what: String): Nothing = throw AtgeoDecodingException(what)

/**
 * A tile payload as read: its usable records, and its attribution (contract §1), which is null unless the
 * payload's `attribution` is an array.
 */
internal data class TilePayload(val records: List<TileRecord>, val attribution: List<Attribution>?)

/**
 * An `attribution` list (contract §1): null unless an array. An element is kept only if it is an object whose `dataset`
 * and `collection` are both strings; `release`, `source` and `attribution` are strings or absent and `licenses` the
 * string elements of an array or absent, each read on its own. Served order, duplicates included.
 */
internal fun readAttribution(element: JsonElement?): List<Attribution>? = (element as? JsonArray)?.mapNotNull { e ->
    val o = e as? JsonObject ?: return@mapNotNull null
    Attribution(
        dataset = o["dataset"].stringOrNull() ?: return@mapNotNull null,
        collection = o["collection"].stringOrNull() ?: return@mapNotNull null,
        release = o["release"].stringOrNull(),
        source = o["source"].stringOrNull(),
        licenses = (o["licenses"] as? JsonArray)?.mapNotNull { it.stringOrNull() },
        attribution = o["attribution"].stringOrNull(),
    )
}

/** The usable records and attribution of a tile payload. Throws AtgeoDecodingException when there is no `records` array. */
internal fun readTilePayload(body: JsonElement): TilePayload {
    val records = (body as? JsonObject)?.get("records") as? JsonArray ?: unusable("tile payload has no records array")
    return TilePayload(
        records.mapNotNull { element ->
            val record = element as? JsonObject ?: return@mapNotNull null
            val value = record["value"] as? JsonObject ?: return@mapNotNull null
            TileRecord(value, record["uri"].stringOrNull(), record["importance"].int64OrNull())
        },
        attribution = readAttribution((body as JsonObject)["attribution"]),
    )
}

internal data class TileList(val tiles: List<String>, val overview: Boolean)

/** A getTiles answer. Throws AtgeoDecodingException when there is no `tiles` array; non-string entries are skipped. */
internal fun readTileList(body: JsonElement): TileList {
    val obj = body as? JsonObject ?: unusable("getTiles body is not an object")
    val tiles = obj["tiles"] as? JsonArray ?: unusable("getTiles body has no tiles array")
    return TileList(tiles.mapNotNull { it.stringOrNull() }, obj["overview"].truthy())
}

/** A getPlace answer: the place's value, its importance and its attribution, always a list (contract §1, §9). */
internal data class PlaceAnswer(val value: JsonObject, val importance: Long?, val attribution: List<Attribution>)

/** A getPlace answer. Throws AtgeoDecodingException when there is no object `value`. */
internal fun readPlace(body: JsonElement): PlaceAnswer {
    val obj = body as? JsonObject ?: unusable("getPlace body is not an object")
    val value = obj["value"] as? JsonObject ?: unusable("getPlace body has no value object")
    return PlaceAnswer(value, obj["importance"].int64OrNull(), readAttribution(obj["attribution"]).orEmpty())
}

private fun JsonObject.string(key: String): String? = this[key].stringOrNull()

// An optional list: absent unless an array; elements that do not map are dropped.
private fun JsonObject.strings(key: String): List<String>? = (this[key] as? JsonArray)?.mapNotNull { it.stringOrNull() }

private fun <T> JsonObject.objects(key: String, map: (JsonObject) -> T): List<T>? =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(map) }

private fun collectionRef(o: JsonObject) = CollectionRef(o.string("dataset"), o.string("collection"))

private fun attribution(o: JsonObject) = CollectionAttribution(
    dataset = o.string("dataset"),
    collection = o.string("collection"),
    release = o.string("release"),
    source = o.string("source"),
    licenses = o.strings("licenses"),
    attribution = o.string("attribution"),
)

private fun collectionInfo(o: JsonObject) = CollectionInfo(
    dataset = o.string("dataset"),
    collection = o.string("collection"),
    release = o.string("release"),
    source = o.string("source"),
    licenses = o.strings("licenses"),
    attribution = o.string("attribution"),
    recordCount = o["recordCount"].int64OrNull(),
    locationFormats = o.strings("locationFormats"),
    containmentTypes = o.strings("containmentTypes"),
    categories = o.objects("categories") { CollectionInfo.CategoryCount(it.string("value"), it["count"].int64OrNull()) },
    attributes = o.strings("attributes"),
)

/** A describeService answer. Throws AtgeoDecodingException when the body is not an object. */
internal fun readServiceDescription(body: JsonElement): ServiceDescription {
    val obj = body as? JsonObject ?: unusable("describeService body is not an object")
    return ServiceDescription(
        did = obj.string("did"),
        maxTiles = obj["maxTiles"].int64OrNull(),
        tilesets = obj.objects("tilesets") {
            ServiceDescription.ServedTileset(it.string("slug"), it.objects("collections", ::collectionRef).orEmpty())
        }.orEmpty(),
    )
}

/** A describeTileset answer. Throws AtgeoDecodingException when the body is not an object. */
internal fun readTileset(body: JsonElement): Tileset {
    val obj = body as? JsonObject ?: unusable("describeTileset body is not an object")
    return Tileset(
        slug = obj.string("slug"),
        publishedAt = obj.string("publishedAt"),
        extent = (obj["extent"] as? JsonObject)?.let {
            Tileset.Extent(it.string("north"), it.string("south"), it.string("east"), it.string("west"))
        },
        collections = obj.objects("collections", ::collectionInfo).orEmpty(),
        references = obj.objects("references", ::attribution),
        overview = (obj["overview"] as? JsonObject)?.let { Tileset.Overview(it["records"].int64OrNull()) },
    )
}
