package org.atgeo.client

import kotlin.math.PI
import kotlin.math.sin

private const val JW_THRESHOLD = 0.6

private class Ranked(val hit: SearchHit, val specificity: Double, val index: Int)

// Relative spherical area; a point is 0, so it ranks before any box containing it.
private fun specificity(locations: Locations): Double {
    val bbox = locations.bbox
    if (locations.point != null || bbox == null) return 0.0
    val rad = PI / 180
    var width = 0.0
    for (s in bbox.segments) width += (s.hi - s.lo) * rad
    return (sin(bbox.north * rad) - sin(bbox.south * rad)) * width
}

internal fun round3(n: Double): Double = Math.round(n * 1000) / 1000.0

/**
 * The sign the reference's subtraction comparators give (`a - b`), without subtracting: -0 ties
 * with 0. A NaN distance, which only out-of-range place coordinates produce, sorts after every
 * number instead of making the comparator inconsistent; see CONFORMANCE.md.
 */
internal fun compareNumbers(a: Double, b: Double): Int = when {
    a < b -> -1
    a > b -> 1
    a == b -> 0
    else -> compareValues(a.isNaN(), b.isNaN())
}

// Each returns negative when `a` sorts first (contract §8).
private val byScore = Comparator<Ranked> { a, b -> compareNumbers(round3(b.hit.score ?: 0.0), round3(a.hit.score ?: 0.0)) }
private val byImportance = Comparator<Ranked> { a, b -> compareValues(b.hit.importance ?: 0L, a.hit.importance ?: 0L) }
private val byDistance = Comparator<Ranked> { a, b -> compareNumbers(a.hit.distance, b.hit.distance) }
private val bySpecificity = Comparator<Ranked> { a, b -> compareNumbers(a.specificity, b.specificity) }
// A null origin sorts after every string; compareValues would put it first.
private val byOrigin: Comparator<Ranked> = compareBy(nullsLast(naturalOrder<String>())) { it.hit.origin }
// The sort is stable already; the input index puts that guarantee in the code.
private val byIndex: Comparator<Ranked> = compareBy { it.index }

private fun order(sort: SortMode): Comparator<Ranked> = when (sort) {
    SortMode.DISTANCE -> byDistance.then(bySpecificity).then(byImportance).then(byScore).then(byOrigin)
    SortMode.SIMILARITY -> byScore.then(byDistance).then(byOrigin)
    SortMode.FUSED -> byScore.then(byImportance).then(byDistance).then(byOrigin)
}.then(byIndex)

/**
 * Scores, filters and orders records that already passed the region filter (contract §7, §8).
 * `query` is already normalized; "" means no text.
 */
internal fun rank(
    records: List<TileRecord>,
    region: Region,
    query: String,
    sort: SortMode,
    collectionOf: (TileRecord) -> CollectionKey? = { null },
): List<SearchHit> {
    val middle = center(region)
    val ranked = ArrayList<Ranked>(records.size)
    for (record in records) {
        val score = if (query.isNotEmpty()) textScore(query, record.value) else null
        if (score != null && score < JW_THRESHOLD) continue
        val locations = locate(record.value)
        // Never null here: the region filter already required a location.
        val distance = distanceTo(locations, middle.lat, middle.lon) ?: continue
        val hit = SearchHit(recordKey(record), collectionOf(record), record.value, record.importance, distance, score)
        ranked.add(Ranked(hit, specificity(locations), ranked.size))
    }
    return ranked.sortedWith(order(sort)).map { it.hit }
}
