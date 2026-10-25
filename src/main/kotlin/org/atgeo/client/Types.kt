package org.atgeo.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.ArrayList
import java.util.Collections
import java.util.Objects

/** How [AtgeoClient.searchPlaces] orders its results (contract §8). */
public enum class SortMode { FUSED, DISTANCE, SIMILARITY }

// The result and describe types are plain classes with internal constructors, not data classes, so
// that a field can be added without breaking apps compiled against an earlier version: a data class's
// constructor, copy() and componentN() all change shape when a field is added. equals, hashCode and
// toString behave as a data class's would.

public class SearchResult internal constructor(
    public val places: List<SearchHit>,
    /** False if any tile failed to load, so the results are partial; [tileFailures] says which and why. */
    public val complete: Boolean,
    /** True if the server answered with only the region's most important places. */
    public val overview: Boolean,
    public val tiles: TileCounts,
    /** Each tile that failed, in the order of the tile URLs, not the order they failed in. Empty when [complete]. */
    public val tileFailures: List<TileFailure>,
    /**
     * The union of the attribution of every tile the search used, one entry per dataset and collection,
     * sorted by dataset then collection (contract §4.10). Always a list; read-only.
     */
    public val attribution: List<Attribution>,
) {
    public class TileCounts internal constructor(public val fetched: Int, public val failed: Int) {
        override fun equals(other: Any?): Boolean = other is TileCounts && fetched == other.fetched && failed == other.failed
        override fun hashCode(): Int = Objects.hash(fetched, failed)
        override fun toString(): String = "TileCounts(fetched=$fetched, failed=$failed)"
    }

    override fun equals(other: Any?): Boolean = other is SearchResult && places == other.places &&
        complete == other.complete && overview == other.overview && tiles == other.tiles && tileFailures == other.tileFailures &&
        attribution == other.attribution
    override fun hashCode(): Int = Objects.hash(places, complete, overview, tiles, tileFailures, attribution)
    override fun toString(): String = "SearchResult(places=$places, complete=$complete, overview=$overview, tiles=$tiles, " +
        "tileFailures=$tileFailures, attribution=$attribution)"
}

/**
 * A tile a search could not use (contract §4.4). [url], [kind] and [status] are the same in every
 * atgeo SDK; [error] is this platform's, so it is left out of [equals] and [hashCode]. Nothing in it
 * comes from the search text or the region.
 */
public class TileFailure internal constructor(
    /** The tile URL as the server's tile list gave it. */
    public val url: String,
    public val kind: Kind,
    /** The HTTP status for [Kind.STATUS]; null for every other kind. */
    public val status: Int?,
    /**
     * What failed: an [AtgeoException] for [Kind.STATUS]; for [Kind.DECODE] usually an
     * [AtgeoDecodingException]; for [Kind.TRANSPORT] whatever the transport threw; for [Kind.REFUSED]
     * a `MalformedURLException`.
     */
    public val error: Throwable,
) {
    /** Where the tile failed. */
    public enum class Kind {
        /** The URL is not an absolute `http` or `https` URL, or carries credentials. No request was made. */
        REFUSED,

        /** The server answered with a status outside 2xx. */
        STATUS,

        /** A 2xx whose body is not a usable tile payload: not JSON, or no `records` array. */
        DECODE,

        /**
         * The transport threw: a network failure, a timeout, a body over `maxBodyBytes`, or anything a
         * transport of the app's own throws.
         */
        TRANSPORT,
    }

    override fun equals(other: Any?): Boolean = other is TileFailure && url == other.url && kind == other.kind && status == other.status
    override fun hashCode(): Int = Objects.hash(url, kind, status)
    override fun toString(): String = "TileFailure(url=$url, kind=$kind, status=$status, error=$error)"
}

/** The dataset and collection a hit belongs to (contract §8). Just the two names, whatever its tile's attribution says. */
public class CollectionKey internal constructor(public val dataset: String, public val collection: String) {
    override fun equals(other: Any?): Boolean = other is CollectionKey && dataset == other.dataset && collection == other.collection
    override fun hashCode(): Int = Objects.hash(dataset, collection)
    override fun toString(): String = "CollectionKey(dataset=$dataset, collection=$collection)"
}

/**
 * A credit for one collection (`org.atgeo.defs#collectionAttribution`), read leniently (contract §1). Unlike
 * [CollectionAttribution], which describes a tileset, [dataset] and [collection] are always present. Immutable:
 * [licenses] is a read-only list that cannot be changed through a cast either.
 */
public class Attribution internal constructor(
    public val dataset: String,
    public val collection: String,
    public val release: String? = null,
    public val source: String? = null,
    licenses: List<String>? = null,
    public val attribution: String? = null,
) {
    /** The licenses as served; a copy that cannot be changed, through a cast or otherwise. */
    public val licenses: List<String>? = licenses?.let { Collections.unmodifiableList(ArrayList(it)) }

    override fun equals(other: Any?): Boolean = other is Attribution && dataset == other.dataset && collection == other.collection &&
        release == other.release && source == other.source && licenses == other.licenses && attribution == other.attribution
    override fun hashCode(): Int = Objects.hash(dataset, collection, release, source, licenses, attribution)
    override fun toString(): String = "Attribution(dataset=$dataset, collection=$collection, release=$release, " +
        "source=$source, licenses=$licenses, attribution=$attribution)"
}

public class SearchHit internal constructor(
    public val origin: String?,
    /**
     * The collection the hit belongs to, matched against its tile's attribution (contract §8); null when
     * none matches, including a hit identified by its uri.
     */
    public val collection: CollectionKey?,
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

    override fun equals(other: Any?): Boolean = other is SearchHit && origin == other.origin && collection == other.collection &&
        place == other.place && importance == other.importance && distance == other.distance && score == other.score
    override fun hashCode(): Int = Objects.hash(origin, collection, place, importance, distance, score)
    override fun toString(): String = "SearchHit(origin=$origin, collection=$collection, place=$place, " +
        "importance=$importance, distance=$distance, score=$score)"
}

public class PlaceRecord internal constructor(
    public val origin: String,
    public val place: JsonObject,
    public val importance: Long?,
    /** The collections the place's data comes from, as served and in served order (contract §9); empty when the source has none. Read-only. */
    public val attribution: List<Attribution>,
) {
    /** `place.name` if it is a string. */
    public val name: String? get() = place["name"].stringOrNull()

    /** The place's point and box, read as a search reads them. */
    public val location: PlaceLocation get() = placeLocation(place)

    override fun equals(other: Any?): Boolean = other is PlaceRecord && origin == other.origin && place == other.place &&
        importance == other.importance && attribution == other.attribution
    override fun hashCode(): Int = Objects.hash(origin, place, importance, attribution)
    override fun toString(): String =
        "PlaceRecord(origin=$origin, place=$place, importance=$importance, attribution=$attribution)"
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
    /** The server's tile budget. Informational (contract §9). */
    public val maxTiles: Long? = null,
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

    override fun equals(other: Any?): Boolean =
        other is ServiceDescription && did == other.did && maxTiles == other.maxTiles && tilesets == other.tilesets
    override fun hashCode(): Int = Objects.hash(did, maxTiles, tilesets)
    override fun toString(): String = "ServiceDescription(did=$did, maxTiles=$maxTiles, tilesets=$tilesets)"
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
    /** The tileset's overview band, when it has one (contract §9). */
    public val overview: Overview? = null,
) {
    @Serializable
    public class Overview internal constructor(
        /** The number of records in the band. */
        public val records: Long? = null,
    ) {
        override fun equals(other: Any?): Boolean = other is Overview && records == other.records
        override fun hashCode(): Int = Objects.hash(records)
        override fun toString(): String = "Overview(records=$records)"
    }

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
        extent == other.extent && collections == other.collections && references == other.references &&
        overview == other.overview
    override fun hashCode(): Int = Objects.hash(slug, publishedAt, extent, collections, references, overview)
    override fun toString(): String = "Tileset(slug=$slug, publishedAt=$publishedAt, extent=$extent, " +
        "collections=$collections, references=$references, overview=$overview)"
}

/** One usable record of a tile: the rest of the payload is dropped (contract §1). */
internal data class TileRecord(val value: JsonObject, val uri: String? = null, val importance: Long? = null)
