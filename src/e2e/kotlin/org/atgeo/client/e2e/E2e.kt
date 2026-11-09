package org.atgeo.client.e2e

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.atgeo.client.AtgeoClient
import org.atgeo.client.KeywordRegionMode
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

    val nearby = client.searchNearby(region, dataset = dataset, collection = collection, sort = SortMode.DISTANCE)
    check(nearby.complete) { "${nearby.tiles.failed} tile(s) failed: ${nearby.tileFailures}" }
    check(nearby.places.isNotEmpty()) { "no places in region" }
    val distances = nearby.places.map { it.distance }
    check(distances.withIndex().all { (i, d) -> d <= radius && (i == 0 || d >= distances[i - 1]) }) { "not distance-ordered within radius" }
    log("searchNearby (no text)", "${nearby.places.size} places from ${nearby.tiles.fetched} tile(s)")

    val named = nearby.places.firstOrNull { it.origin != null && !nameOf(it.place).isNullOrEmpty() }
    checkNotNull(named) { "no named place in region" }
    val name = nameOf(named.place)!!
    val matched = client.searchNearby(region, text = name, dataset = dataset, collection = collection)
    check(matched.places.any { it.origin == named.origin && it.score != null }) { "named place missing from text search" }
    check((matched.places[0].score ?: 0.0) > 0) { "top text hit has no score" }
    log("searchNearby (text)", "\"$name\" -> top hit ${nameOf(matched.places[0].place)}, ${matched.places.size} places")

    // The cold search disclosed the region it fetched; the warm one on the same client was a full cache hit and disclosed none (contract §4.11).
    check(!nearby.disclosed.keyword && nearby.disclosed.region == 0.01) { "searchNearby disclosed ${nearby.disclosed}" }
    check(matched.disclosed.region == null) { "warm searchNearby disclosed ${matched.disclosed}" }

    // Sends the name to the server: the keyword methods may.
    val keyword = client.searchByKeyword(name, dataset = dataset, collection = collection)
    check(keyword.disclosed.keyword && keyword.disclosed.region == null) { "searchByKeyword disclosed ${keyword.disclosed}" }
    check(keyword.places.any { it.origin == named.origin }) { "named place missing from keyword search" }
    log("searchByKeyword", "\"$name\" -> ${keyword.places.size} places")

    // The name is a word match nearby, so the fallback answers locally and sends nothing (contract §19).
    val fallback = client.searchNearbyThenByKeyword(region, dataset = dataset, keyword = name, keywordRegion = KeywordRegionMode.DEGREE, collection = collection)
    check(!fallback.disclosed.keyword && fallback.disclosed.region == null) { "searchNearbyThenByKeyword disclosed ${fallback.disclosed}" }
    check(fallback.places.any { it.origin == named.origin }) { "named place missing from the fallback search" }
    log("searchNearbyThenByKeyword", "\"$name\" -> ${fallback.places.size} places, nothing sent")

    // One autocompleter call: the keyword qualifies, so it is sent (as the keyword methods may).
    val typed = client.autocompleteByKeyword(dataset, collection).searchByKeyword(name)
    check(typed.disclosed.keyword && typed.disclosed.region == null) { "autocompleter disclosed ${typed.disclosed}" }
    check(typed.places.any { it.origin == named.origin }) { "named place missing from the autocompleter" }
    log("autocompleteByKeyword", "\"$name\" -> ${typed.places.size} places")

    val origin = named.origin!!
    val record = AtgeoClient(server = server).getPlace(origin)
    check(nameOf(record.place) == name) { "getPlace returned ${nameOf(record.place)}, expected $name" }
    log("getPlace", "$origin -> ${nameOf(record.place)}")
}
