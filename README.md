# atgeo-client for Kotlin

A Kotlin client for the atgeo gazetteer served by [garganorn](https://github.com/schuyler/garganorn), which searches places without telling the server what you searched for.

It's a port of [@atgeo/client](https://github.com/schuyler/atgeo-sdk-js) and returns the same places in the same order for the same server answers. One artifact runs on the JVM (11 or later) and on Android (API 24 or later). Its only dependencies are kotlinx-coroutines and kotlinx-serialization; HTTP goes through `HttpURLConnection` unless you supply something better.

## Synopsis

```kotlin
import org.atgeo.client.AtgeoClient
import org.atgeo.client.Region

val atgeo = AtgeoClient()

val result = atgeo.searchPlaces(
    region = Region.Circle(lat = 43.0, lon = -71.45, radius = 3000.0), // metres
    text = "pizza",
)

for (hit in result.places) {
    val point = hit.location.point
    println("${hit.name}  ${point?.lat},${point?.lon}  ${hit.distance.toInt()} m  ${"%.2f".format(hit.score)}  ${hit.origin}")
}
```

`searchPlaces` is a `suspend` function; call it from any coroutine, the main thread included. Misspellings are fine; matching is fuzzy. If `complete` is `false`, some tiles failed to load and the results are partial. If `overview` is `true`, the region was too big to search in full and you got only its most important places.

## Installation

This isn't on Maven Central yet; it stays unpublished until beta testing ends. Get it from [JitPack](https://jitpack.io), pinned to a commit:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.schuyler:atgeo-sdk-kotlin:<commit-sha>")
}
```

Pin a full commit SHA rather than a branch: the API is still changing, and breaking changes land on `main`. The first build of a commit takes JitPack a minute or two.

To work on it from a checkout, `includeBuild("../atgeo-sdk-kotlin")` in your `settings.gradle.kts` and depend on `org.atgeo:atgeo-client`. That's how `android-sample/` uses it.

## Constructing a client

```kotlin
val atgeo = AtgeoClient(
    server = "http://localhost:8000",          // default: https://places.atgeo.org
    transport = OkHttpTransport(),             // default: HttpUrlConnectionTransport()
    cacheBudgetBytes = 10L * 1024 * 1024,      // default: 50 MiB
    computeDispatcher = Dispatchers.Default,   // the default
)
```

Make one per server and share it; it's thread-safe, and its caches are what make repeat searches free. `computeDispatcher` is where tiles are parsed and results ranked, so a search started on `Dispatchers.Main` does none of that work there.

### Transports

`HttpTransport` is one function, `suspend fun get(url: String): HttpResponse`, so a test or an app with its own HTTP stack can pass a lambda. The default, `HttpUrlConnectionTransport`, needs nothing and asks for gzip (tiles are about a tenth the size), but on the JDK a cancelled request keeps its socket reading until the body ends or the read timeout (30 seconds by default) fires; your coroutine is released at once either way. If your app cancels a lot (a search per keystroke, say) or already ships OkHttp, copy [`OkHttpTransport.kt`](android-sample/app/src/main/kotlin/org/atgeo/sample/OkHttpTransport.kt) from the sample app. It is short, and it cancels on the wire.

Whatever transport you use, don't give it a disk cache, or the client's promise to keep nothing on disk goes with it.

## API

### `searchPlaces(region, text?, dataset?, collection?, sort?): SearchResult`

| Parameter | Meaning |
|---|---|
| `region` | Required. `Region.Circle(lat, lon, radius)` with the radius in metres, or `Region.Bbox(minLon, minLat, maxLon, maxLat)` in degrees. Note the order: a circle is latitude first, a box longitude first (GeoJSON's order), so name the arguments. A bbox with `minLon > maxLon` crosses the antimeridian. |
| `text` | Optional search text, matched fuzzily against each place's `name` and its `variants[].name` after lowercasing and stripping accents and extra whitespace. Places scoring below 0.6 are dropped. |
| `dataset` | Optional, such as `openstreetmap.org`. Defaults to `overturemaps.org`. |
| `collection` | Optional, such as `way`. |
| `sort` | `SortMode.FUSED` (default), `DISTANCE` or `SIMILARITY`. |

With neither `dataset` nor `collection` you search `overturemaps.org` / `place`; with only `collection`, that collection of `overturemaps.org`; with only `dataset`, every collection of that dataset together; with both, that collection.

```kotlin
data class SearchResult(
    val places: List<SearchHit>,
    val complete: Boolean,        // false if any tile failed to load
    val overview: Boolean,        // true if the server answered with its coarse overview
    val tiles: TileCounts,        // fetched and failed
)

data class SearchHit(
    val origin: String?,          // place.origin, else the tile record's uri, else null
    val place: JsonObject,        // the record value, exactly as served
    val importance: Long?,        // relative to its tileset; null if it has none
    val distance: Double,         // metres from the region's centre to the nearest point of the place; 0 if it contains the centre
    val score: Double?,           // 0 to 1 text similarity; null without text
) {
    val name: String?             // place["name"] if it's a string
    val location: PlaceLocation   // point and/or box, parsed as the client parsed them for distance
}
```

`place` is the gazetteer's record untouched. `name` and `location` save you the two reads every app needs: `location.point` is the first usable `geo` entry of `place.locations`, `location.bbox` the first usable `bbox` entry (`west > east` crosses the antimeridian), coordinates already parsed from the strings they arrive as. For any other field, `place["field"].stringOrNull()` gives a string field or `null`, without the casts `JsonElement` otherwise needs. `PlaceRecord` has the same `name` and `location`.

`FUSED` orders by similarity, breaking near-ties by importance, then distance. `DISTANCE` orders nearest first, then the smaller place, then importance, then similarity. `SIMILARITY` orders by similarity, then distance, and needs `text`. Scores are compared to three decimals and remaining ties break on `origin`, so the order is deterministic.

The [JavaScript README](https://github.com/schuyler/atgeo-sdk-js#readme) has the long version: overview answers, places that appear in several tiles, and how ties are broken. All of it applies here unchanged.

### `getPlace(origin): PlaceRecord`

Returns `PlaceRecord(origin, place, importance)` for the `origin` of a hit. If a cached tile holds the place, no request is made; otherwise the client asks the server directly.

### `describeService(): ServiceDescription`

The server's DID and the tilesets it serves. Cached for an hour.

### `describeTileset(slug): Tileset`

A tileset's collections, attribution and extent. Not cached. The describe types are read leniently: a field that is missing or of the wrong type comes back `null`, never as an exception. They're `@Serializable` in case you want to store them.

### Errors

- `AtgeoException` has the server's error `code` (`PlaceNotFound`, `BboxTooLarge`, `DatasetNotFound` and so on), or `HttpError` when the server didn't send one.
- `searchPlaces` throws `IllegalArgumentException` for an invalid region or for `SIMILARITY` without text, before any request.
- A failed `getTiles` fails the search. A failed tile does not: it's skipped, counted in `tiles.failed`, and `complete` is `false`.
- A 2xx body with nothing usable in it throws `kotlinx.serialization.SerializationException`. Network errors come through as whatever the transport throws, usually `IOException`. A transport that gives up by throwing `CancellationException` while your coroutine is still running (its own `withTimeout`, say) is reported as `SocketTimeoutException`, or as `IOException` for anything but a timeout, so that it can't end your coroutine silently.
- Cancelling the calling coroutine stops the search and throws `CancellationException`.

`SerializationException` is a subclass of `IllegalArgumentException`, so a `catch (e: IllegalArgumentException)` meant for a bad region also catches a garbled server answer. Catch `SerializationException` first if you tell the two apart. And as anywhere in coroutines, don't let a `catch (e: Exception)` swallow `CancellationException`; rethrow it.

## Privacy

Your search text never leaves the device. Neither does your precise location.

A search asks the server which tiles cover a bounding box snapped outward to a 0.01° grid (roughly 1 km), downloads those tiles, and does the matching, filtering and ranking locally. The server learns the cell, the dataset and collection, and which tiles you fetched that weren't already cached. It also sees your IP address and the transport's own fixed headers. The [JavaScript README](https://github.com/schuyler/atgeo-sdk-js#privacy) lists exactly what goes on the wire; it's the same here, and the test suite checks it against a request-capturing fake server.

## Caching

Tile lists and the service description are cached for an hour, tiles in memory up to `cacheBudgetBytes`, least recently used out first. A repeat search over a warm region makes no requests at all. Nothing is persisted; a new client starts cold.

## Notes

The behaviour is defined once for every port in [docs/port-contract.md](https://github.com/schuyler/atgeo-sdk-js/blob/main/docs/port-contract.md), and the Kotlin-specific decisions are in [docs/kotlin-sdk-spec.md](https://github.com/schuyler/atgeo-sdk-js/blob/main/docs/kotlin-sdk-spec.md). Where this port reads malformed input differently from the reference (`"0x1p3"` is 8 here and 0 there), [CONFORMANCE.md](CONFORMANCE.md) lists it. A compliant server never sends any of it.

The library is a plain Kotlin/JVM module, which means the compiler will happily let you call a JDK method Android doesn't have. [animalsniffer](https://github.com/xvik/gradle-animalsniffer-plugin) checks every build against the Android API 24 signature, so `readAllBytes()` fails the build instead of crashing a phone.

Most of this port was written by Claude, working from the spec and the reference, with the reference's test suite ported one to one and run against the shared fixtures. The two clients were also compared against the live server: across five searches they returned the same 4,131 hits in the same order, with matching distances and scores.

## Development

```sh
./gradlew build                            # tests, plus the Android API 24 check
./gradlew -p android-sample assembleDebug  # needs an Android SDK
ATGEO_SERVER=http://localhost:8000 ./gradlew e2e
```

The end-to-end check takes the same `ATGEO_SERVER`, `ATGEO_DATASET`, `ATGEO_COLLECTION` and `ATGEO_REGION` variables as the JavaScript one.

`src/test/resources/fixtures/` holds the shared fixtures, copied from atgeo-sdk-js at the commit named in `SOURCE`, and the garganorn lexicons, with their own `SOURCE`.

## License

BSD 3-Clause; see [LICENSE](LICENSE).
