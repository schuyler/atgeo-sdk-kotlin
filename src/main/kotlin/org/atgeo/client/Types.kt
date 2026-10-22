package org.atgeo.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.Objects

/** How [AtgeoClient.searchPlaces] orders its results (contract §8). */
public enum class SortMode { FUSED, DISTANCE, SIMILARITY }

// The result and describe types are plain classes with internal constructors, not data classes, so
// that a field can be added without breaking apps compiled against an earlier version: a data class's
// constructor, copy() and componentN() all change shape when a field is added. equals, hashCode and
// toString behave as a data class's would.

public class SearchResult internal constructor(
    public val places: List<SearchHit>,
    /** False if any tile failed to load, so the results are partial. */
    public val complete: Boolean,
    /** True if the server answered with only the region's most important places. */
    public val overview: Boolean,
    public val tiles: TileCounts,
) {
    public class TileCounts internal constructor(public val fetched: Int, public val failed: Int) {
        override fun equals(other: Any?): Boolean = other is TileCounts && fetched == other.fetched && failed == other.failed
        override fun hashCode(): Int = Objects.hash(fetched, failed)
        override fun toString(): String = "TileCounts(fetched=$fetched, failed=$failed)"
    }

    override fun equals(other: Any?): Boolean = other is SearchResult &&
        places == other.places && complete == other.complete && overview == other.overview && tiles == other.tiles
    override fun hashCode(): Int = Objects.hash(places, complete, overview, tiles)
    override fun toString(): String = "SearchResult(places=$places, complete=$complete, overview=$overview, tiles=$tiles)"
}

public class SearchHit internal constructor(
    public val origin: String?,
    /** The place exactly as served. */
    public val place: JsonObject,
    public val importance: Long?,
    /** Metres from the region's centre to the nearest point of the place. */
    public val distance: Double,
    /** 0 to 1; null when the search had no text. */
    public val score: Double?,
) {
    /** `place.name` if it is a string. */
    public val name: String? get() = place["name"].stringOrNull()

    /** The place's point and box, as the client read them to compute [distance]. */
    public val location: PlaceLocation get() = placeLocation(place)

    override fun equals(other: Any?): Boolean = other is SearchHit && origin == other.origin && place == other.place &&
        importance == other.importance && distance == other.distance && score == other.score
    override fun hashCode(): Int = Objects.hash(origin, place, importance, distance, score)
    override fun toString(): String =
        "SearchHit(origin=$origin, place=$place, importance=$importance, distance=$distance, score=$score)"
}

public class PlaceRecord internal constructor(public val origin: String, public val place: JsonObject, public val importance: Long?) {
    /** `place.name` if it is a string. */
    public val name: String? get() = place["name"].stringOrNull()

    /** The place's point and box, read as a search reads them. */
    public val location: PlaceLocation get() = placeLocation(place)

    override fun equals(other: Any?): Boolean =
        other is PlaceRecord && origin == other.origin && place == other.place && importance == other.importance
    override fun hashCode(): Int = Objects.hash(origin, place, importance)
    override fun toString(): String = "PlaceRecord(origin=$origin, place=$place, importance=$importance)"
}

// The describe types. Every field is optional so that a missing one never
// fails a call; responses are mapped by hand in Wire.kt, never through these
// generated serializers, which would throw on a wrong-typed field (contract
// §1). @Serializable is for apps that want to persist them, and they mirror
// lexicons that gain fields, which is why they are not data classes either.

@Serializable
public class CollectionRef internal constructor(public val dataset: String? = null, public val collection: String? = null) {
    override fun equals(other: Any?): Boolean = other is CollectionRef && dataset == other.dataset && collection == other.collection
    override fun hashCode(): Int = Objects.hash(dataset, collection)
    override fun toString(): String = "CollectionRef(dataset=$dataset, collection=$collection)"
}

@Serializable
public class ServiceDescription internal constructor(
    public val did: String? = null,
    public val tilesets: List<ServedTileset> = emptyList(),
) {
    @Serializable
    public class ServedTileset internal constructor(
        public val slug: String? = null,
        public val collections: List<CollectionRef> = emptyList(),
    ) {
        override fun equals(other: Any?): Boolean = other is ServedTileset && slug == other.slug && collections == other.collections
        override fun hashCode(): Int = Objects.hash(slug, collections)
        override fun toString(): String = "ServedTileset(slug=$slug, collections=$collections)"
    }

    override fun equals(other: Any?): Boolean = other is ServiceDescription && did == other.did && tilesets == other.tilesets
    override fun hashCode(): Int = Objects.hash(did, tilesets)
    override fun toString(): String = "ServiceDescription(did=$did, tilesets=$tilesets)"
}

@Serializable
public class CollectionAttribution internal constructor(
    public val dataset: String? = null,
    public val collection: String? = null,
    public val release: String? = null,
    public val source: String? = null,
    public val licenses: List<String>? = null,
    public val attribution: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is CollectionAttribution && dataset == other.dataset &&
        collection == other.collection && release == other.release && source == other.source &&
        licenses == other.licenses && attribution == other.attribution
    override fun hashCode(): Int = Objects.hash(dataset, collection, release, source, licenses, attribution)
    override fun toString(): String = "CollectionAttribution(dataset=$dataset, collection=$collection, release=$release, " +
        "source=$source, licenses=$licenses, attribution=$attribution)"
}

@Serializable
public class CollectionInfo internal constructor(
    public val dataset: String? = null,
    public val collection: String? = null,
    public val release: String? = null,
    public val source: String? = null,
    public val licenses: List<String>? = null,
    public val attribution: String? = null,
    public val recordCount: Long? = null,
    public val locationFormats: List<String>? = null,
    public val containmentTypes: List<String>? = null,
    public val categories: List<CategoryCount>? = null,
    public val attributes: List<String>? = null,
) {
    @Serializable
    public class CategoryCount internal constructor(public val value: String? = null, public val count: Long? = null) {
        override fun equals(other: Any?): Boolean = other is CategoryCount && value == other.value && count == other.count
        override fun hashCode(): Int = Objects.hash(value, count)
        override fun toString(): String = "CategoryCount(value=$value, count=$count)"
    }

    override fun equals(other: Any?): Boolean = other is CollectionInfo && dataset == other.dataset &&
        collection == other.collection && release == other.release && source == other.source &&
        licenses == other.licenses && attribution == other.attribution && recordCount == other.recordCount &&
        locationFormats == other.locationFormats && containmentTypes == other.containmentTypes &&
        categories == other.categories && attributes == other.attributes
    override fun hashCode(): Int = Objects.hash(
        dataset, collection, release, source, licenses, attribution,
        recordCount, locationFormats, containmentTypes, categories, attributes,
    )
    override fun toString(): String = "CollectionInfo(dataset=$dataset, collection=$collection, release=$release, " +
        "source=$source, licenses=$licenses, attribution=$attribution, recordCount=$recordCount, " +
        "locationFormats=$locationFormats, containmentTypes=$containmentTypes, categories=$categories, attributes=$attributes)"
}

@Serializable
public class Tileset internal constructor(
    public val slug: String? = null,
    public val publishedAt: String? = null,
    public val extent: Extent? = null,
    public val collections: List<CollectionInfo> = emptyList(),
    public val references: List<CollectionAttribution>? = null,
) {
    @Serializable
    public class Extent internal constructor(
        public val north: String? = null,
        public val south: String? = null,
        public val east: String? = null,
        public val west: String? = null,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Extent && north == other.north && south == other.south && east == other.east && west == other.west
        override fun hashCode(): Int = Objects.hash(north, south, east, west)
        override fun toString(): String = "Extent(north=$north, south=$south, east=$east, west=$west)"
    }

    override fun equals(other: Any?): Boolean = other is Tileset && slug == other.slug && publishedAt == other.publishedAt &&
        extent == other.extent && collections == other.collections && references == other.references
    override fun hashCode(): Int = Objects.hash(slug, publishedAt, extent, collections, references)
    override fun toString(): String =
        "Tileset(slug=$slug, publishedAt=$publishedAt, extent=$extent, collections=$collections, references=$references)"
}

/** One usable record of a tile: the rest of the payload is dropped (contract §1). */
internal data class TileRecord(val value: JsonObject, val uri: String? = null, val importance: Long? = null)
