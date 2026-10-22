package org.atgeo.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// west and east are kept as served for PlaceLocation; the geometry uses segments.
internal data class BoxLocation(val south: Double, val north: Double, val segments: List<LonSegment>, val west: Double, val east: Double)

internal data class Locations(val point: LatLon?, val bbox: BoxLocation?)

/**
 * Where a place is, read from its `locations` exactly as the client reads it to rank and filter
 * (contract §5): the first usable point and the first usable box, either or both absent. Coordinates
 * may be strings or numbers on the wire; here they are parsed, finite, and not range-checked.
 */
public class PlaceLocation internal constructor(public val point: Point?, public val bbox: Box?) {
    /** A point in degrees. */
    public data class Point(val lat: Double, val lon: Double)

    /**
     * A box in degrees, with the lexicon's names. `west > east` crosses the antimeridian, and ±180
     * are the same meridian.
     */
    public data class Box(val south: Double, val west: Double, val north: Double, val east: Double)

    override fun equals(other: Any?): Boolean = other is PlaceLocation && point == other.point && bbox == other.bbox

    override fun hashCode(): Int = 31 * point.hashCode() + bbox.hashCode()

    override fun toString(): String = "PlaceLocation(point=$point, bbox=$bbox)"
}

internal fun placeLocation(place: JsonObject): PlaceLocation {
    val (point, bbox) = locate(place)
    return PlaceLocation(
        point?.let { PlaceLocation.Point(it.lat, it.lon) },
        bbox?.let { PlaceLocation.Box(it.south, it.west, it.north, it.east) },
    )
}

private const val GEO = "community.lexicon.location.geo"
private const val BBOX = "community.lexicon.location.bbox"

// A coordinate is a JSON string holding a number or a JSON number (contract §5). toDoubleOrNull's
// margins against parseFloat are listed in CONFORMANCE.md. Arrays and everything else are no number.
private fun coordinate(element: JsonElement?): Double? {
    val primitive = element as? JsonPrimitive ?: return null
    val n = if (primitive.isString) primitive.content.toDoubleOrNull() else primitive.doubleOrNull
    return n?.takeIf { it.isFinite() }
}

/** The first usable point and the first usable box of `value.locations` (contract §5). */
internal fun locate(value: JsonObject): Locations {
    var point: LatLon? = null
    var bbox: BoxLocation? = null
    for (element in (value["locations"] as? JsonArray).orEmpty()) {
        val loc = element as? JsonObject ?: continue
        val type = loc["\$type"].stringOrNull()
        if (type == GEO && point == null) {
            val lat = coordinate(loc["latitude"])
            val lon = coordinate(loc["longitude"])
            if (lat != null && lon != null) point = LatLon(lat, lon)
        } else if (type == BBOX && bbox == null) {
            val south = coordinate(loc["south"])
            val north = coordinate(loc["north"])
            val west = coordinate(loc["west"])
            val east = coordinate(loc["east"])
            if (south != null && north != null && west != null && east != null) {
                bbox = BoxLocation(south, north, lonSegments(west, east), west, east)
            }
        }
    }
    return Locations(point, bbox)
}

/** `value.origin` if it is a string, else the record's `uri`, else null (contract §4.6). */
internal fun recordKey(r: TileRecord): String? = r.value["origin"].stringOrNull() ?: r.uri

/** Keeps the first record for each key, in order; records without a key all pass. */
internal fun dedupe(records: List<TileRecord>): List<TileRecord> {
    val seen = HashSet<String>()
    return records.filter { r ->
        val key = recordKey(r)
        key == null || seen.add(key)
    }
}

private fun angularGap(a: Double, b: Double): Double {
    val d = abs(a - b) % 360
    return min(d, 360 - d)
}

private fun nearestLon(lon: Double, segments: List<LonSegment>): Double {
    if (lonInSegments(lon, segments)) return lon
    val ends = segments.flatMap { listOf(it.lo, it.hi) }
    // On an exact tie the first endpoint wins.
    return ends.reduce { best, e -> if (angularGap(e, lon) < angularGap(best, lon)) e else best }
}

// ±180 are one meridian, and every meridian meets at a pole.
private fun covers(south: Double, north: Double, segments: List<LonSegment>, lat: Double, lon: Double): Boolean {
    if (lat < south || lat > north) return false
    return abs(lat) == 90.0 ||
        lonInSegments(lon, segments) ||
        (abs(lon) == 180.0 && lonInSegments(-lon, segments))
}

// The box-to-box form of covers(): boxes touching ±180 meet at the seam, and boxes reaching a pole meet there.
private fun meets(box: BoxLocation, south: Double, north: Double, segments: List<LonSegment>): Boolean {
    val lo = max(box.south, south)
    val hi = min(box.north, north)
    if (lo > hi) return false
    val twins = box.segments.flatMap { s ->
        listOfNotNull(
            s,
            if (s.hi == 180.0) LonSegment(-180.0, -180.0) else null,
            if (s.lo == -180.0) LonSegment(180.0, 180.0) else null,
        )
    }
    return hi == 90.0 || lo == -90.0 || lonSegmentsOverlap(twins, segments)
}

/**
 * Metres from (lat, lon) to the nearest point of the place; null without a location (contract §6).
 * Containment is tested explicitly so it yields exactly 0 rather than trig round-trip noise.
 */
internal fun distanceTo(locations: Locations, lat: Double, lon: Double): Double? {
    val point = locations.point
    val bbox = locations.bbox
    val contained = if (point != null) {
        covers(point.lat, point.lat, listOf(LonSegment(point.lon, point.lon)), lat, lon)
    } else {
        bbox != null && covers(bbox.south, bbox.north, bbox.segments, lat, lon)
    }
    if (contained) return 0.0
    if (point != null) return haversine(lat, lon, point.lat, point.lon)
    if (bbox == null) return null
    // Along the nearest meridian, cos(d) = R cos(phi - phiC); phiC is where that meridian comes closest.
    val lonStar = nearestLon(lon, bbox.segments)
    val phi0 = lat * PI / 180
    val dLon = angularGap(lonStar, lon) * PI / 180
    val phiC = atan2(sin(phi0), cos(phi0) * cos(dLon)) * 180 / PI
    return minOf(
        haversine(lat, lon, min(max(phiC, bbox.south), bbox.north), lonStar),
        haversine(lat, lon, bbox.south, lonStar),
        haversine(lat, lon, bbox.north, lonStar),
    )
}

internal fun inRegion(locations: Locations, region: Region): Boolean = when (region) {
    is Region.Bbox -> {
        val segments = lonSegments(region.minLon, region.maxLon)
        val point = locations.point
        val bbox = locations.bbox
        if (point != null) {
            covers(region.minLat, region.maxLat, segments, point.lat, point.lon)
        } else {
            bbox != null && meets(bbox, region.minLat, region.maxLat, segments)
        }
    }
    is Region.Circle -> {
        val d = distanceTo(locations, region.lat, region.lon)
        d != null && d <= region.radius
    }
}
