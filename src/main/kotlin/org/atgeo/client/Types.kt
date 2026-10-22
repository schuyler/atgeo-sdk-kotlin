package org.atgeo.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** How [AtgeoClient.searchPlaces] orders its results (contract §8). */
public enum class SortMode { FUSED, DISTANCE, SIMILARITY }

public data class SearchResult(
    val places: List<SearchHit>,
    /** False if any tile failed to load, so the results are partial. */
    val complete: Boolean,
    /** True if the server answered with only the region's most important places. */
    val overview: Boolean,
    val tiles: TileCounts,
) {
    public data class TileCounts(val fetched: Int, val failed: Int)
}

public data class SearchHit(
    val origin: String?,
    /** The place exactly as served. */
    val place: JsonObject,
    val importance: Long?,
    /** Metres from the region's centre to the nearest point of the place. */
    val distance: Double,
    /** 0 to 1; null when the search had no text. */
    val score: Double?,
)

public data class PlaceRecord(val origin: String, val place: JsonObject, val importance: Long?)

// The describe types. Every field is optional so that a missing one never
// fails a call; responses are mapped by hand in Wire.kt, never through these
// generated serializers, which would throw on a wrong-typed field (contract
// §1). @Serializable is for apps that want to persist them.

@Serializable
public data class CollectionRef(val dataset: String? = null, val collection: String? = null)

@Serializable
public data class ServiceDescription(val did: String? = null, val tilesets: List<ServedTileset> = emptyList()) {
    @Serializable
    public data class ServedTileset(val slug: String? = null, val collections: List<CollectionRef> = emptyList())
}

@Serializable
public data class CollectionAttribution(
    val dataset: String? = null,
    val collection: String? = null,
    val release: String? = null,
    val source: String? = null,
    val licenses: List<String>? = null,
    val attribution: String? = null,
)

@Serializable
public data class CollectionInfo(
    val dataset: String? = null,
    val collection: String? = null,
    val release: String? = null,
    val source: String? = null,
    val licenses: List<String>? = null,
    val attribution: String? = null,
    val recordCount: Long? = null,
    val locationFormats: List<String>? = null,
    val containmentTypes: List<String>? = null,
    val categories: List<CategoryCount>? = null,
    val attributes: List<String>? = null,
) {
    @Serializable
    public data class CategoryCount(val value: String? = null, val count: Long? = null)
}

@Serializable
public data class Tileset(
    val slug: String? = null,
    val publishedAt: String? = null,
    val extent: Extent? = null,
    val collections: List<CollectionInfo> = emptyList(),
    val references: List<CollectionAttribution>? = null,
) {
    @Serializable
    public data class Extent(
        val north: String? = null,
        val south: String? = null,
        val east: String? = null,
        val west: String? = null,
    )
}

/** One usable record of a tile: the rest of the payload is dropped (contract §1). */
internal data class TileRecord(val value: JsonObject, val uri: String? = null, val importance: Long? = null)
