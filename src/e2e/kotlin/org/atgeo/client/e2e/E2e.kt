package org.atgeo.client.e2e

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.atgeo.client.AtgeoClient
import org.atgeo.client.Region
import org.atgeo.client.SortMode

// End to end against a running garganorn, the same steps and variables as scripts/e2e.ts.
// Run with ./gradlew e2e; it is not part of ./gradlew test.

private fun log(step: String, detail: String) = println("ok  $step: $detail")

private fun nameOf(place: JsonObject) = (place["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content

public fun main(): Unit = runBlocking {
    val server = System.getenv("ATGEO_SERVER") ?: "http://localhost:8000"
    val dataset = System.getenv("ATGEO_DATASET") ?: "openstreetmap.org"
    val collection = System.getenv("ATGEO_COLLECTION")
    // With a collection set, the tileset must serve that collection, not just the dataset.
    fun wanted(d: String?, c: String?) = d == dataset && (collection == null || c == collection)
    // "lat,lon,radius" in metres; the default is Manchester, NH, inside the local OSM New Hampshire tileset.
    val (lat, lon, radius) = (System.getenv("ATGEO_REGION") ?: "43.0,-71.45,3000").split(",").map { it.trim().toDouble() }
    val region = Region.Circle(lat, lon, radius)

    val client = AtgeoClient(server = server)

    val service = client.describeService()
    val served = service.tilesets.firstOrNull { t -> t.collections.any { wanted(it.dataset, it.collection) } }
    checkNotNull(served) { "$dataset${collection?.let { "/$it" }.orEmpty()} not served" }
    log("describeService", "${service.did}, ${service.tilesets.size} tileset(s)")

    val tileset = client.describeTileset(checkNotNull(served.slug) { "served tileset has no slug" })
    check(tileset.collections.any { wanted(it.dataset, it.collection) }) { "describeTileset does not list the dataset and collection" }
    log("describeTileset", "${tileset.slug}, ${tileset.collections.size} collection(s)")

    val nearby = client.searchPlaces(region, dataset = dataset, collection = collection, sort = SortMode.DISTANCE)
    check(nearby.complete) { "${nearby.tiles.failed} tile(s) failed: ${nearby.tileFailures}" }
    check(nearby.places.isNotEmpty()) { "no places in region" }
    val distances = nearby.places.map { it.distance }
    check(distances.withIndex().all { (i, d) -> d <= radius && (i == 0 || d >= distances[i - 1]) }) { "not distance-ordered within radius" }
    log("searchPlaces (no text)", "${nearby.places.size} places from ${nearby.tiles.fetched} tile(s)")

    val named = nearby.places.firstOrNull { it.origin != null && !nameOf(it.place).isNullOrEmpty() }
    checkNotNull(named) { "no named place in region" }
    val name = nameOf(named.place)!!
    val matched = client.searchPlaces(region, text = name, dataset = dataset, collection = collection)
    check(matched.places.any { it.origin == named.origin && it.score != null }) { "named place missing from text search" }
    check((matched.places[0].score ?: 0.0) > 0) { "top text hit has no score" }
    log("searchPlaces (text)", "\"$name\" -> top hit ${nameOf(matched.places[0].place)}, ${matched.places.size} places")

    val origin = named.origin!!
    val record = AtgeoClient(server = server).getPlace(origin)
    check(nameOf(record.place) == name) { "getPlace returned ${nameOf(record.place)}, expected $name" }
    log("getPlace", "$origin -> ${nameOf(record.place)}")
}
