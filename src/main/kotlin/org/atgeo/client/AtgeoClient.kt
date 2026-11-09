package org.atgeo.client

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.MalformedURLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

private const val MAX_TILES_IN_FLIGHT = 8
private const val ONE_HOUR_MILLIS = 3_600_000L

/**
 * A client for the atgeo gazetteer that searches places without telling the server what was
 * searched for. Thread-safe; share one per server for the life of the process.
 *
 * @param transport how requests are made. The default uses `HttpURLConnection` and no dependency.
 * @param cacheBudgetBytes cap on the in-memory tile cache, counted as the size of the tiles' response
 *   bodies, which is what the cache holds. The latest search's tiles are also kept parsed, at 5 to 7
 *   times their size, outside the budget.
 * @param computeDispatcher where tiles are parsed and results ranked, so that a search started on
 *   a UI thread does no CPU work there.
 */
public class AtgeoClient internal constructor(
    server: String,
    transport: HttpTransport,
    cacheBudgetBytes: Long,
    private val computeDispatcher: CoroutineDispatcher,
    clock: () -> Long,
) {
    public constructor(
        server: String = DEFAULT_SERVER,
        transport: HttpTransport = HttpUrlConnectionTransport(),
        cacheBudgetBytes: Long = DEFAULT_CACHE_BUDGET_BYTES,
        computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(server, transport, cacheBudgetBytes, computeDispatcher, System::currentTimeMillis)

    public companion object {
        public const val DEFAULT_SERVER: String = "https://places.atgeo.org"
        public const val DEFAULT_CACHE_BUDGET_BYTES: Long = 50L * 1024 * 1024
    }

    private val xrpc = Xrpc(server, transport)

    // Guards the caches. Never held across a transport call.
    private val mutex = Mutex()
    private val tiles = TileCache(cacheBudgetBytes)
    private val parsed = ParsedTiles()
    private val tileLists = TtlCache<TileList>(ONE_HOUR_MILLIS, clock)
    private val service = TtlCache<ServiceDescription>(ONE_HOUR_MILLIS, clock)

    /**
     * Searches `region`, optionally matching `text` fuzzily, and returns ranked places (contract §4).
     * Neither the text nor the region's precise coordinates leave the device: the server sees only
     * the region snapped outward to a 0.01° grid. No method whose name lacks `Keyword` sends text.
     *
     * Searches the collection of `dataset` that `collection` names, or the whole dataset without one.
     * `dataset` is required and has no default; an empty string goes to the server as given.
     *
     * A tile that fails to load does not fail the search: the result is marked incomplete, and the
     * tile is reported in [NearbySearchResult.tileFailures] with its URL and where it failed.
     *
     * @throws IllegalArgumentException for a null `dataset` (only a Java or reflective caller can pass one),
     *   an invalid region, or [SortMode.SIMILARITY] without text,
     *   before any request is made.
     * @throws AtgeoException when a getTiles call is refused by the server.
     */
    public suspend fun searchNearby(
        region: Region,
        dataset: String,
        text: String? = null,
        collection: String? = null,
        sort: SortMode = SortMode.FUSED,
    ): NearbySearchResult {
        requireDataset(dataset)
        validateRegion(region)
        val query = normalize(text ?: "")
        require(sort != SortMode.SIMILARITY || query.isNotEmpty()) { "sort SIMILARITY requires text" }
        return withContext(computeDispatcher) { search(region, query, sort, dataset, collection) }
    }

    /**
     * Asks the server to match places by name, so **it sends `keyword` to the server** as given, and says so in
     * [KeywordSearchResult.disclosed]. The server keeps no log of it (garganorn's privacy design), a hostile
     * operator can record what it receives. Use [searchNearby] to keep the text on the device.
     *
     * `region`, when given, is sent snapped outward to whole degrees (a one-degree grid, coarser than the nearby
     * search's) and as one request. The call is not cached. A keyword the server cannot use (no word of two or
     * more characters) is not an argument error: it goes to the server, which answers `KeywordRequired`.
     *
     * @throws IllegalArgumentException for a null `dataset` or an invalid `region`, before any request is made.
     * @throws AtgeoException when the server refuses the request (`KeywordRequired`, `InvalidRequest` for a
     *   keyword over 256 bytes of UTF-8, `DatasetNotFound`, ...).
     */
    public suspend fun searchByKeyword(
        keyword: String,
        dataset: String,
        collection: String? = null,
        region: KeywordRegion? = null,
    ): KeywordSearchResult {
        requireDataset(dataset)
        if (region != null) validateKeywordRegion(region)
        return keywordSearch(keyword, dataset, collection, region?.let { keywordBbox(it.west, it.south, it.east, it.north) })
    }

    private suspend fun keywordSearch(keyword: String, dataset: String, collection: String?, bbox: String?): KeywordSearchResult {
        val params = buildList {
            add("dataset" to dataset)
            if (collection != null) add("collection" to collection)
            add("q" to keyword)
            if (bbox != null) add("bbox" to bbox)
        }
        // Not cached: nothing from the answer enters any cache (contract §10).
        return withContext(computeDispatcher) {
            val places = readSearchPlaces(xrpc.query("org.atgeo.searchPlaces", params))
            KeywordSearchResult(
                places.map { KeywordHit(it.origin, collectionOf(it.value, it.attribution), it.value, it.importance) },
                mergeAttribution(places.map { it.attribution }),
                Disclosure(keyword = true, region = if (bbox != null) 1.0 else null),
            )
        }
    }

    /**
     * Searches nearby with `keyword` as the text, which stays on the device, and only if that finds no word match
     * (match tier 1, contract §7) and the keyword could qualify (contract §17), **sends `keyword` to the server**
     * as [searchByKeyword] does. With [KeywordRegionMode.DEGREE] that request carries the nearby region as a box
     * snapped to whole degrees; with [KeywordRegionMode.NONE] it carries no region. The caller decides, every time.
     * [FallbackSearchResult.disclosed] says what went out.
     *
     * The merged result lists the keyword hits first, then the nearby hits the keyword hits did not repeat.
     * A server refusal of the keyword with `KeywordRequired` keeps the nearby result. Any other failure of the
     * keyword request fails the call, and the nearby results are lost; there is no result to carry `disclosed`,
     * though the keyword may have reached the server.
     *
     * @throws IllegalArgumentException for a null `dataset`, an invalid region, or [SortMode.SIMILARITY] with a
     *   keyword that normalizes to nothing, before any request is made.
     */
    public suspend fun searchNearbyThenByKeyword(
        region: Region,
        dataset: String,
        keyword: String,
        keywordRegion: KeywordRegionMode,
        collection: String? = null,
        sort: SortMode = SortMode.FUSED,
    ): FallbackSearchResult {
        requireDataset(dataset)
        validateRegion(region)
        val query = normalize(keyword)
        require(sort != SortMode.SIMILARITY || query.isNotEmpty()) { "sort SIMILARITY requires a keyword" }
        val nearby = withContext(computeDispatcher) { search(region, query, sort, dataset, collection) }
        val solid = withContext(computeDispatcher) { nearby.places.any { matchTier(query, it.place) == 1 } }
        if (solid || !qualifies(keyword)) return fallbackResult(nearby, nearby.places, nearby.attribution, nearby.disclosed)
        val degree = keywordRegion == KeywordRegionMode.DEGREE
        val bbox = if (degree) {
            val box = when (region) {
                is Region.Bbox -> doubleArrayOf(region.west, region.south, region.east, region.north)
                is Region.Circle -> circleBox(region.lat, region.lon, region.radius)
            }
            keywordBbox(box[0], box[1], box[2], box[3])
        } else {
            null
        }
        val found = try {
            keywordSearch(keyword, dataset, collection, bbox)
        } catch (e: AtgeoException) {
            // The server refused a keyword the qualifying check let through (contract §18, step 8): keep the nearby
            // result, which is what skipping the request would have returned, but report the keyword as sent.
            if (e.code != "KeywordRequired") throw e
            return fallbackResult(nearby, nearby.places, nearby.attribution, Disclosure(true, nearby.disclosed.region ?: if (degree) 1.0 else null))
        }
        val keywordOrigins = found.places.mapNotNullTo(HashSet()) { it.origin }
        val merged = found.places + nearby.places.filter { it.origin == null || it.origin !in keywordOrigins }
        return fallbackResult(
            nearby, merged, mergeAttribution(listOf(nearby.attribution, found.attribution)),
            Disclosure(true, nearby.disclosed.region ?: found.disclosed.region),
        )
    }

    private fun fallbackResult(nearby: NearbySearchResult, places: List<PlaceHit>, attribution: List<Attribution>, disclosed: Disclosure) =
        FallbackSearchResult(places, nearby.complete, nearby.overview, nearby.tiles, nearby.tileFailures, attribution, disclosed)

    /**
     * An autocompleter for one text field: its [KeywordAutocomplete.searchByKeyword] supersedes its own previous
     * call, and no other autocompleter's. **Every keyword that qualifies is sent to the server as typed, prefixes
     * included**; see [KeywordAutocomplete.searchByKeyword].
     *
     * @throws NullPointerException for a null `dataset` (only a Java or reflective caller can pass one): this method
     *   is not `suspend`, so the compiler's own parameter check throws it, naming `dataset`.
     * @throws IllegalArgumentException for an invalid `region`, here, not at the first call.
     */
    public fun autocompleteByKeyword(
        dataset: String,
        collection: String? = null,
        region: KeywordRegion? = null,
    ): KeywordAutocomplete {
        requireDataset(dataset)
        if (region != null) validateKeywordRegion(region)
        return KeywordAutocomplete(region) { keyword, bbox -> keywordSearch(keyword, dataset, collection, bbox) }
    }

    // The Kotlin compiler emits no null check for the parameters of a suspend function, so a caller that
    // passes null (Java, reflection) gets the argument error here, before the region is looked at (contract §4.1).
    private fun requireDataset(dataset: String?) {
        require(dataset != null) { "dataset is required" }
    }

    private suspend fun search(
        region: Region,
        query: String,
        sort: SortMode,
        dataset: String,
        collection: String?,
    ): NearbySearchResult = coroutineScope {
        // Whether anything went to the server that tells it the client is interested in the area (contract §4.11):
        // a getTiles request, or a tile fetch the client attempted. A refused URL is not attempted.
        val sent = AtomicBoolean(false)
        // One per antimeridian half, concurrently; the first failure fails the search.
        val lists = queryBboxes(region).map { bbox -> async { tileList(dataset, collection, bbox, sent) } }.awaitAll()
        val urls = lists.flatMap { it.tiles }.distinct().sorted()
        val overview = lists.any { it.overview }

        // The reference's mapLimit: workers claim URLs in order, at most 8 in flight. Each writes only
        // its own slots, so no counter is shared between threads, and failures come out in URL order.
        val slots = arrayOfNulls<TileOutcome>(urls.size)
        val next = AtomicInteger(0)
        coroutineScope {
            repeat(min(MAX_TILES_IN_FLIGHT, urls.size)) {
                launch {
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= urls.size) break
                        slots[i] = loadTile(urls[i], sent)
                    }
                }
            }
        }
        val used = urls.indices.mapNotNull { i -> (slots[i] as? TileOutcome.Used)?.let { urls[i] to it } }
        val tileFailures = slots.mapNotNull { (it as? TileOutcome.Failed)?.failure }
        mutex.withLock { parsed.replace(used.associate { (url, tile) -> url to ParsedTiles.Parsed(tile.entry, tile.records, tile.attribution) }) }

        val prefix = collection?.let { collectionPrefix(dataset, it) }
        // Each record knows its tile's attribution, for the hit's collection (contract §8).
        val tileOf = java.util.IdentityHashMap<TileRecord, List<Attribution>>()
        for ((_, tile) in used) for (r in tile.records) tileOf[r] = tile.attribution
        // dedupe picks a winner before the region filter runs, as the reference does (contract §4.6).
        val records = dedupe(used.flatMap { (_, tile) -> tile.records })
            .filter { prefix == null || inCollection(it.value, prefix) }
            .filter { inRegion(locate(it.value), region) }
        val attribution = mergeAttribution(used.map { (_, tile) -> tile.attribution })
        val counts = NearbySearchResult.TileCounts(fetched = used.size, failed = tileFailures.size)
        NearbySearchResult(
            rank(records, region, query, sort, overview) { r -> collectionOf(r.value, tileOf.getValue(r)) },
            tileFailures.isEmpty(), overview, counts, tileFailures, attribution,
            Disclosure(keyword = false, region = if (sent.get()) 0.01 else null),
        )
    }

    private suspend fun tileList(dataset: String, collection: String?, bbox: String, sent: AtomicBoolean): TileList {
        val key = "$dataset|${collection.orEmpty()}|$bbox"
        mutex.withLock { tileLists.get(key) }?.let { return it }
        val params = buildList {
            add("dataset" to dataset)
            if (collection != null) add("collection" to collection)
            add("bbox" to bbox)
        }
        sent.set(true)
        val list = readTileList(xrpc.query("org.atgeo.getTiles", params))
        mutex.withLock { tileLists.set(key, list) }
        return list
    }

    private sealed interface TileOutcome {
        class Used(val entry: TileCache.Entry, val records: List<TileRecord>, val attribution: List<Attribution>) : TileOutcome

        class Failed(val failure: TileFailure) : TileOutcome
    }

    // The tile's cache entry and records, or how it failed. Checks the cache when the worker reaches the
    // URL, as the reference does, so recency is touched in the same order.
    //
    // What counts as a failed tile is fixed by where an error arises, not its type (contract §4.4): the
    // URL check, the transport, the status check and decoding are guarded, and each is a kind. The cache
    // insert is the client's own work and is not, so that a bug there fails the search instead of
    // passing for a run of bad tiles. Only the search's own cancellation propagates from the guarded
    // part: a transport's withTimeout throws TimeoutCancellationException, a CancellationException, and
    // that is a failed tile. No Error is caught except a StackOverflowError from decodeTile; an
    // OutOfMemoryError must not pass for one bad tile.
    private suspend fun loadTile(url: String, sent: AtomicBoolean): TileOutcome {
        if (!isHttpUrl(url)) {
            val error = MalformedURLException("tile URL is not an absolute http or https URL without credentials")
            return TileOutcome.Failed(TileFailure(url, TileFailure.Kind.REFUSED, null, error))
        }
        mutex.withLock { tiles.get(url)?.let { it to parsed.get(url, it) } }?.let { (entry, memo) ->
            if (memo != null) return TileOutcome.Used(entry, memo.records, memo.attribution)
            // A cached body parsed once already, so a deterministic parser cannot fail on it again.
            return try {
                decodeTile(entry.body).let { TileOutcome.Used(entry, it.records, it.attribution.orEmpty()) }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                TileOutcome.Failed(TileFailure(url, TileFailure.Kind.DECODE, null, e))
            }
        }
        var kind = TileFailure.Kind.TRANSPORT
        var status: Int? = null
        // Attempted, whatever comes of it: the server is told the client is interested in the area (contract §4.11).
        sent.set(true)
        val (body, payload) = try {
            val response = xrpc.send(url)
            kind = TileFailure.Kind.STATUS
            status = response.status
            val body = xrpc.checkStatus(response)
            kind = TileFailure.Kind.DECODE
            status = null
            body to decodeTile(body)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            return TileOutcome.Failed(TileFailure(url, kind, status, e))
        }
        val entry = TileCache.Entry(body, payload.records.mapNotNullTo(HashSet()) { recordKey(it) })
        mutex.withLock { tiles.set(url, entry) }
        return TileOutcome.Used(entry, payload.records, payload.attribution.orEmpty())
    }

    // The server's input caused a stack overflow in the decoder, so it is a decode failure (contract §4.4);
    // only that Error, never Error in general. kotlinx.serialization refuses deep nesting with a
    // SerializationException before it can overflow, so this is a guard for later versions.
    private fun decodeTile(body: ByteArray): TilePayload = try {
        readTilePayload(parseBody(body))
    } catch (e: StackOverflowError) {
        throw AtgeoDecodingException("tile payload is too deeply nested", e)
    }

    /**
     * The place with this origin: from any cached tile without a request, else one getPlace call
     * sending the origin and nothing else (contract §9).
     *
     * @throws AtgeoException `PlaceNotFound` when the server has no such place.
     */
    public suspend fun getPlace(origin: String): PlaceRecord {
        // The scan walks every cached tile's keys and may parse the one that matches, so it runs off the
        // caller's thread. Within that tile the first record with the key wins.
        val cached = withContext(computeDispatcher) {
            val found = mutex.withLock { tiles.find(origin)?.let { (url, entry) -> entry to parsed.get(url, entry) } }
            found?.let { (entry, memo) ->
                val tile = memo?.let { it.records to it.attribution } ?: readTilePayload(parseBody(entry.body)).let { it.records to it.attribution.orEmpty() }
                tile.first.first { recordKey(it) == origin } to tile.second
            }
        }
        // The attribution is the source tile's, as served; a copy, so that changing the answer changes nothing cached.
        if (cached != null) return PlaceRecord(origin, cached.first.value, cached.first.importance, frozen(cached.second))
        val answer = readPlace(xrpc.query("org.atgeo.getPlace", listOf("uri" to origin)))
        return PlaceRecord(origin, answer.value, answer.importance, frozen(answer.attribution))
    }

    /** What the server serves. Cached for an hour. */
    public suspend fun describeService(): ServiceDescription {
        mutex.withLock { service.get("") }?.let { return it }
        val description = readServiceDescription(xrpc.query("org.atgeo.describeService"))
        mutex.withLock { service.set("", description) }
        return description
    }

    /**
     * A tileset's collections, attribution and extent. Never cached.
     *
     * @throws AtgeoException `TilesetNotFound` for an unknown slug.
     */
    public suspend fun describeTileset(slug: String): Tileset =
        readTileset(xrpc.query("org.atgeo.describeTileset", listOf("slug" to slug)))
}
