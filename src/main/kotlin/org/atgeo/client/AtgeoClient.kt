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
     * the region snapped outward to a 0.01° grid.
     *
     * Searches the collection of `dataset` that `collection` names, or the whole dataset without one.
     * `dataset` is required and has no default; an empty string goes to the server as given.
     *
     * A tile that fails to load does not fail the search: the result is marked incomplete, and the
     * tile is reported in [SearchResult.tileFailures] with its URL and where it failed.
     *
     * @throws IllegalArgumentException for an invalid region, or [SortMode.SIMILARITY] without text,
     *   before any request is made.
     * @throws AtgeoException when a getTiles call is refused by the server.
     */
    public suspend fun searchPlaces(
        region: Region,
        dataset: String,
        text: String? = null,
        collection: String? = null,
        sort: SortMode = SortMode.FUSED,
    ): SearchResult {
        // The Kotlin compiler emits no null check for the parameters of a suspend function, so a caller that
        // passes null (Java, reflection) gets the argument error here, before the region is looked at (contract §4.1).
        @Suppress("SENSELESS_COMPARISON")
        require(dataset != null) { "dataset is required" }
        validateRegion(region)
        val query = normalize(text ?: "")
        require(sort != SortMode.SIMILARITY || query.isNotEmpty()) { "sort SIMILARITY requires text" }
        return withContext(computeDispatcher) { search(region, query, sort, dataset, collection) }
    }

    private suspend fun search(
        region: Region,
        query: String,
        sort: SortMode,
        dataset: String,
        collection: String?,
    ): SearchResult = coroutineScope {
        // One per antimeridian half, concurrently; the first failure fails the search.
        val lists = queryBboxes(region).map { bbox -> async { tileList(dataset, collection, bbox) } }.awaitAll()
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
                        slots[i] = loadTile(urls[i])
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
        val counts = SearchResult.TileCounts(fetched = used.size, failed = tileFailures.size)
        SearchResult(
            rank(records, region, query, sort) { r -> collectionOf(r.value, tileOf.getValue(r)) },
            tileFailures.isEmpty(), overview, counts, tileFailures, attribution,
        )
    }

    private suspend fun tileList(dataset: String, collection: String?, bbox: String): TileList {
        val key = "$dataset|${collection.orEmpty()}|$bbox"
        mutex.withLock { tileLists.get(key) }?.let { return it }
        val params = buildList {
            add("dataset" to dataset)
            if (collection != null) add("collection" to collection)
            add("bbox" to bbox)
        }
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
    private suspend fun loadTile(url: String): TileOutcome {
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
