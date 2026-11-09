package org.atgeo.client

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Where to search. Neither class validates on construction; [AtgeoClient.searchNearby] does (contract §3). */
public sealed interface Region {
    /** A circle; `radius` in metres. */
    public data class Circle(val lat: Double, val lon: Double, val radius: Double) : Region

    /** A box in degrees, in the order of the getTiles bbox string. `west > east` crosses the antimeridian. */
    public data class Bbox(val west: Double, val south: Double, val east: Double, val north: Double) : Region
}

private const val DEG_TO_M = 111194.927
private const val EARTH_RADIUS_M = 6371008.8
private const val EPSILON = 1e-9

/** An inclusive longitude range; a box crossing the antimeridian has two. */
internal data class LonSegment(val lo: Double, val hi: Double)

internal data class LatLon(val lat: Double, val lon: Double)

private fun inRange(n: Double, limit: Double) = n.isFinite() && abs(n) <= limit

private fun requireValidBbox(west: Double, south: Double, east: Double, north: Double) = require(
    inRange(west, 180.0) && inRange(east, 180.0) && inRange(south, 90.0) && inRange(north, 90.0) && south <= north,
) { "invalid bbox" }

/** Throws IllegalArgumentException for a keyword region contract §3 rejects: the bbox checks. */
internal fun validateKeywordRegion(region: KeywordRegion) = requireValidBbox(region.west, region.south, region.east, region.north)

/** Throws IllegalArgumentException for a region contract §3 rejects. */
internal fun validateRegion(region: Region) {
    fun ok(n: Double, limit: Double) = inRange(n, limit)
    when (region) {
        is Region.Bbox -> requireValidBbox(region.west, region.south, region.east, region.north)
        is Region.Circle -> require(
            ok(region.lat, 90.0) && ok(region.lon, 180.0) && region.radius.isFinite() && region.radius >= 0,
        ) { "invalid circle" }
    }
}

internal fun center(region: Region): LatLon = when (region) {
    is Region.Circle -> LatLon(region.lat, region.lon)
    is Region.Bbox -> {
        var lon = if (region.west <= region.east) {
            (region.west + region.east) / 2
        } else {
            (region.west + region.east + 360) / 2
        }
        if (lon > 180) lon -= 360
        LatLon((region.south + region.north) / 2, lon)
    }
}

internal fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val rad = PI / 180
    val a = sin(((lat2 - lat1) * rad) / 2).pow(2) +
        cos(lat1 * rad) * cos(lat2 * rad) * sin(((lon2 - lon1) * rad) / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
}

internal fun lonSegments(minLon: Double, maxLon: Double): List<LonSegment> =
    if (minLon <= maxLon) {
        listOf(LonSegment(minLon, maxLon))
    } else {
        listOf(LonSegment(minLon, 180.0), LonSegment(-180.0, maxLon))
    }

internal fun lonInSegments(lon: Double, segments: List<LonSegment>): Boolean =
    segments.any { lon >= it.lo && lon <= it.hi }

internal fun lonSegmentsOverlap(a: List<LonSegment>, b: List<LonSegment>): Boolean =
    a.any { x -> b.any { y -> x.lo <= y.hi && y.lo <= x.hi } }

// The circle's enclosing box as [minLon, minLat, maxLon, maxLat]; minLon > maxLon when it wraps.
internal fun circleBox(lat: Double, lon: Double, radius: Double): DoubleArray {
    val half = radius / DEG_TO_M
    val minLat = max(lat - half, -90.0)
    val maxLat = min(lat + half, 90.0)
    // Half-width of a spherical cap: asin(sin r / cos lat). At or past 1 (or NaN) the cap holds a pole.
    val sinHalfLon = sin(half * PI / 180) / cos(lat * PI / 180)
    if (minLat == -90.0 || maxLat == 90.0 || !(sinHalfLon < 1)) return doubleArrayOf(-180.0, minLat, 180.0, maxLat)
    val halfLon = asin(sinHalfLon) * 180 / PI
    fun wrap(x: Double) = if (x > 180) x - 360 else if (x < -180) x + 360 else x
    return doubleArrayOf(wrap(lon - halfLon), minLat, wrap(lon + halfLon), maxLat)
}

// In hundredths of a degree. Math.round is half up like JavaScript's (kotlin.math.round is half to
// even), though the tie rule never matters here: r is used only when x * 100 is within EPSILON of it.
private fun snapDown(x: Double): Long {
    val r = Math.round(x * 100)
    return if (abs(x * 100 - r) < EPSILON) r else floor(x * 100).toLong()
}

private fun snapUp(x: Double): Long {
    val r = Math.round(x * 100)
    return if (abs(x * 100 - r) < EPSILON) r else ceil(x * 100).toLong()
}

private fun snapSpan(min: Double, max: Double, limit: Long): Pair<Long, Long> {
    var lo = snapDown(min)
    var hi = snapUp(max)
    if (lo == hi) {
        if (hi < limit * 100) hi++ else lo--
    }
    return lo to hi
}

// Formatted from the integer: no locale, no floating point, and no negative zero.
private fun fixed(units: Long): String {
    val a = abs(units)
    return (if (units < 0) "-" else "") + a / 100 + "." + (a % 100).toString().padStart(2, '0')
}

/** The snapped getTiles bbox strings, one per antimeridian half, in call order (contract §3). */
internal fun queryBboxes(region: Region): List<String> {
    val box = when (region) {
        is Region.Bbox -> doubleArrayOf(region.west, region.south, region.east, region.north)
        is Region.Circle -> circleBox(region.lat, region.lon, region.radius)
    }
    val (lat0, lat1) = snapSpan(box[1], box[3], 90)
    return lonSegments(box[0], box[2]).map { segment ->
        val (lon0, lon1) = snapSpan(segment.lo, segment.hi, 180)
        listOf(lon0, lat0, lon1, lat1).joinToString(",") { fixed(it) }
    }
}

// A whole-degree span in integers, so no -0 and no ".0" can reach the string. A point or a line widens by one cell.
private fun wholeSpan(lo: Double, hi: Double, limit: Long): Pair<Long, Long> {
    var start = floor(lo).toLong()
    var end = ceil(hi).toLong()
    if (start == end) {
        if (end < limit) end++ else start--
    }
    return start to end
}

/**
 * The `bbox` string of a keyword search: the smallest whole-degree box the server accepts that covers the
 * input, as `west,south,east,north` integers (contract §16). It is always one box, never split at the antimeridian;
 * a crossing box whose snapped edges no longer cross validly becomes the full band.
 */
internal fun keywordBbox(west: Double, south: Double, east: Double, north: Double): String {
    val (s, n) = wholeSpan(south, north, 90)
    // The seams are decided on the input values first: a west of 180 is the meridian at -180, an east of -180 is 180.
    var w = west
    var e = east
    if (w == 180.0 && w > e) w = -180.0
    if (e == -180.0 && w > e) e = 180.0
    if (w > e) {
        val snappedWest = floor(w).toLong()
        val snappedEast = ceil(e).toLong()
        return if (snappedWest > 0 && snappedEast < 0) "$snappedWest,$s,$snappedEast,$n" else "-180,$s,180,$n"
    }
    val (lo, hi) = wholeSpan(w, e, 180)
    return "$lo,$s,$hi,$n"
}
