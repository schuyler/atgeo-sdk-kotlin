package org.atgeo.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.URLDecoder

// A request-capturing HttpTransport mirroring test/helpers/fakeServer.ts. There is no JVM lexicon
// validator, so it checks the contract's minimum (§13) plus what fakeServer.test.ts asserts.

internal const val DEFAULT_SERVER = "https://places.atgeo.org"
private const val PUBLISHED_AT = "2026-01-01T00:00:00Z"

/** The dataset and collection a search names: searchPlaces has no defaults (contract §4.1). */
internal const val OVERTURE_DATASET = "overturemaps.org"
internal const val OVERTURE_COLLECTION = "place"

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
        "locations" to JsonArray(locations),
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

internal fun collectionOf(dataset: String, collection: String, vararg extra: Pair<String, JsonElement>): JsonObject =
    JsonObject(mapOf("dataset" to JsonPrimitive(dataset), "collection" to JsonPrimitive(collection)) + extra)

/** Records are JSON objects, not the client's TileRecord, so malformed ones are expressible. */
internal data class FakeTile(val url: String, val records: List<JsonObject>)

internal data class FakeTileset(
    val slug: String?,
    val collections: List<JsonObject>,
    val references: List<JsonObject>? = null,
    val overview: JsonObject? = null,
    val tiles: List<FakeTile>,
)

internal fun overture(tiles: List<FakeTile>) =
    FakeTileset("overture-place", listOf(collectionOf("overturemaps.org", "place")), tiles = tiles)

/** What `tilesFor` returns in place of the routed getTiles answer: the reference tests' three shapes. */
internal sealed interface TilesAnswer {
    data class Urls(val tiles: List<String>) : TilesAnswer

    data class Listing(val tiles: List<String>, val overview: Boolean? = null) : TilesAnswer

    data class Error(val error: String, val message: String) : TilesAnswer
}

private val BBOX = Regex("""^-?\d+\.\d\d,-?\d+\.\d\d,-?\d+\.\d\d,-?\d+\.\d\d$""")

// The lexicon's format: uri, roughly: a scheme, a colon, optional //, then non-space characters not starting with /.
private val URI_FORMAT = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*:(//)?[^\s/]\S*$""")

// Parameter names per query, and which are required.
private val PARAMS = mapOf(
    "org.atgeo.getTiles" to (setOf("dataset", "collection", "bbox") to setOf("dataset", "bbox")),
    "org.atgeo.getPlace" to (setOf("uri") to setOf("uri")),
    "org.atgeo.describeService" to (emptySet<String>() to emptySet()),
    "org.atgeo.describeTileset" to (setOf("slug") to setOf("slug")),
)

internal class FakeServer(
    private val server: String = DEFAULT_SERVER,
    // Default: one tileset, "overture-place", carrying overturemaps.org / place.
    private val tilesets: List<FakeTileset> = listOf(overture(emptyList())),
    // Replaces the whole getTiles answer after dataset and collection selection has passed.
    private val tilesFor: ((bbox: String) -> TilesAnswer)? = null,
    private val failTiles: Set<String> = emptySet(),
    // Raw responses by URL, bypassing routing and validation (contract §16 bodies).
    private val responses: Map<String, HttpResponse> = emptyMap(),
) : HttpTransport {
    // A client on a multi-threaded dispatcher calls get() from several threads.
    private val lock = Any()
    private val captured = mutableListOf<String>()
    private var offline = false

    /** Every request URL, in arrival order. Method (GET) and body (none) are fixed by the seam. */
    val requests: List<String> get() = synchronized(lock) { captured.toList() }

    /** After this, every request fails as a network failure. */
    fun goOffline() = synchronized(lock) { offline = true }

    init {
        for (tileset in tilesets) {
            require(tileset.slug != null) { "tileset descriptor must have the property \"slug\"" }
            for (tile in tileset.tiles) {
                for (r in tile.records) {
                    val value = r["value"] as? JsonObject ?: throw IllegalArgumentException("tile record must have the property \"value\"")
                    require(value["name"].stringOrNull() != null) { "place record must have the property \"name\"" }
                    val importance = r["importance"]
                    if (importance != null) {
                        val n = (importance as? JsonPrimitive)?.takeUnless { it.isString }?.content
                        require(n != null && Regex("""^-?\d+$""").matches(n)) { "importance must be an integer" }
                    }
                }
            }
        }
    }

    private val did = "did:web:${URI(server).host}"

    override suspend fun get(url: String): HttpResponse {
        synchronized(lock) {
            captured += url
            if (offline) throw IOException("network disabled")
        }
        return responses[url] ?: handle(url)
    }

    private fun json(body: JsonElement, status: Int = 200) = HttpResponse(status, body.toString().toByteArray())

    private fun xrpcError(error: String, message: String, status: Int = 400) = json(
        buildJsonObject {
            put("error", error)
            put("message", message)
        },
        status,
    )

    private fun handle(url: String): HttpResponse {
        if (url.startsWith("$server/xrpc/")) {
            val path = url.substringBefore('?')
            val nsid = path.substringAfterLast('/')
            val (known, required) = PARAMS[nsid] ?: return xrpcError("MethodNotImplemented", "Method $nsid not implemented", 501)
            val params = url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            (params.keys - known).firstOrNull()?.let { return xrpcError("InvalidRequest", "Unexpected parameter $it") }
            (required - params.keys).firstOrNull()?.let { return xrpcError("InvalidRequest", "Params must have the property \"$it\"") }
            params["bbox"]?.let { if (!BBOX.matches(it)) return xrpcError("InvalidRequest", "bbox must match the 0.01° grid format") }
            params["uri"]?.let { if (!URI_FORMAT.matches(it)) return xrpcError("InvalidRequest", "uri must be a uri") }
            return when (nsid) {
                "org.atgeo.getTiles" -> getTiles(params.getValue("dataset"), params["collection"], params.getValue("bbox"))
                "org.atgeo.getPlace" -> getPlace(params.getValue("uri"))
                "org.atgeo.describeService" -> json(
                    buildJsonObject {
                        put("did", did)
                        put("maxTiles", 50)
                        put(
                            "tilesets",
                            buildJsonArray {
                                for (t in tilesets) {
                                    add(
                                        buildJsonObject {
                                            put("slug", t.slug)
                                            put("collections", JsonArray(t.collections.map { collectionOf(it.str("dataset"), it.str("collection")) }))
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
                else -> {
                    val slug = params.getValue("slug")
                    tilesets.firstOrNull { it.slug == slug }?.let { json(descriptorOf(it)) }
                        ?: xrpcError("TilesetNotFound", "Tileset $slug not found")
                }
            }
        }
        for (tileset in tilesets) {
            val tile = tileset.tiles.firstOrNull { it.url == url } ?: continue
            if (tile.url in failTiles) return HttpResponse(500, "upstream error".toByteArray())
            return json(tileBody(tileset, tile.records))
        }
        return HttpResponse(404, "Not found".toByteArray())
    }

    private fun JsonObject.str(key: String) = this[key].stringOrNull().orEmpty()

    private fun getTiles(dataset: String, collection: String?, bbox: String): HttpResponse {
        val withDataset = tilesets.filter { t -> t.collections.any { it.str("dataset") == dataset } }
        if (withDataset.isEmpty()) return xrpcError("DatasetNotFound", "Dataset $dataset not found")
        val selected = withDataset.filter { t ->
            t.collections.any { it.str("dataset") == dataset && (collection == null || it.str("collection") == collection) }
        }
        if (selected.isEmpty()) return xrpcError("CollectionNotFound", "Collection $collection not found")
        val urls = selected.flatMap { t -> t.tiles.map { it.url } }.sorted()
        return when (val answer = tilesFor?.invoke(bbox) ?: TilesAnswer.Urls(urls)) {
            is TilesAnswer.Urls -> json(buildJsonObject { put("tiles", JsonArray(answer.tiles.map(::JsonPrimitive))) })
            is TilesAnswer.Listing -> json(
                buildJsonObject {
                    put("tiles", JsonArray(answer.tiles.map(::JsonPrimitive)))
                    if (answer.overview != null) put("overview", answer.overview)
                },
            )
            is TilesAnswer.Error -> xrpcError(answer.error, answer.message)
        }
    }

    private fun getPlace(origin: String): HttpResponse {
        val parts = origin.split(":")
        val dataset = parts.getOrNull(2)
        val collection = parts.getOrNull(3)
        for (tileset in tilesets) {
            if (tileset.collections.none { it.str("dataset") == dataset && it.str("collection") == collection }) continue
            for (tile in tileset.tiles) {
                val found = tile.records.firstOrNull { (it["value"] as JsonObject)["origin"].stringOrNull() == origin } ?: continue
                return json(
                    buildJsonObject {
                        put("uri", origin)
                        put("url", "$server/$dataset/$collection/${parts.drop(4).joinToString(":")}")
                        found["importance"]?.let { put("importance", it) }
                        put("value", found.getValue("value"))
                        put("attribution", attributionList(tileset))
                    },
                )
            }
        }
        return xrpcError("PlaceNotFound", "Place $origin not found")
    }

    private fun attributionOf(c: JsonObject) =
        JsonObject(c.filterKeys { it in setOf("dataset", "collection", "release", "source", "licenses", "attribution") })

    // The attribution every tile of the tileset carries, and getPlace answers with: its collections, then its references.
    private fun attributionList(tileset: FakeTileset) =
        JsonArray(tileset.collections.map(::attributionOf) + tileset.references.orEmpty())

    private fun tileBody(tileset: FakeTileset, records: List<JsonObject>) = buildJsonObject {
        put("publishedAt", PUBLISHED_AT)
        put("attribution", attributionList(tileset))
        put("records", JsonArray(records))
    }

    private fun descriptorOf(t: FakeTileset) = buildJsonObject {
        put("slug", t.slug)
        put("collections", JsonArray(t.collections))
        if (t.references != null) put("references", JsonArray(t.references))
        if (t.overview != null) put("overview", t.overview)
    }
}

/** A client for the ported suites: compute work on the test scheduler, as spec §5 prescribes. */
internal fun kotlinx.coroutines.test.TestScope.client(
    transport: HttpTransport,
    server: String = DEFAULT_SERVER,
    clock: () -> Long = System::currentTimeMillis,
) = AtgeoClient(server, transport, AtgeoClient.DEFAULT_CACHE_BUDGET_BYTES, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler), clock)

internal fun tileUrl(n: String) = "https://tiles.example/$n.json"

/** The query parameters of a captured URL, decoded. */
internal fun paramsOf(url: String): Map<String, String> =
    url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }

/** Scheme, host and path of a captured URL: the reference's `u.origin + u.pathname`. */
internal fun endpointOf(url: String): String = url.substringBefore('?')

/**
 * Does to a read-only list what a careless app could through a cast: add, reorder, remove, clear. The list
 * may refuse (an unmodifiable view throws); what matters is that nothing cached changes (contract §9).
 */
internal fun tryToChange(list: List<*>?) {
    @Suppress("UNCHECKED_CAST")
    val changeable = list as? MutableList<Any?> ?: return
    for (change in listOf<(MutableList<Any?>) -> Unit>({ it.add("changed") }, { it.reverse() }, { it.removeAt(0) }, { it.clear() })) {
        try {
            change(changeable)
        } catch (_: UnsupportedOperationException) {
        } catch (_: ClassCastException) {
        } catch (_: IndexOutOfBoundsException) {
        }
    }
}
