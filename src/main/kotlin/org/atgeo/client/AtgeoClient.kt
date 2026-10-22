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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

private const val DEFAULT_DATASET = "overturemaps.org"
private const val DEFAULT_COLLECTION = "place"
private const val MAX_TILES_IN_FLIGHT = 8
private const val ONE_HOUR_MILLIS = 3_600_000L

/**
 * A client for the atgeo gazetteer that searches places without telling the server what was
 * searched for. Thread-safe; share one per server for the life of the process.
 *
 * @param transport how requests are made. The default uses `HttpURLConnection` and no dependency.
 * @param cacheBudgetBytes cap on the in-memory tile cache.
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

    // Guards the three caches. Never held across a transport call.
    private val mutex = Mutex()
    private val tiles = TileCache(cacheBudgetBytes)
    private val tileLists = TtlCache<TileList>(ONE_HOUR_MILLIS, clock)
    private val service = TtlCache<ServiceDescription>(ONE_HOUR_MILLIS, clock)

    /**
     * Searches `region`, optionally matching `text` fuzzily, and returns ranked places (contract §4).
     * Neither the text nor the region's precise coordinates leave the device: the server sees only
     * the region snapped outward to a 0.01° grid.
     *
     * With no `dataset` and no `collection`, searches overturemaps.org / place; with a `dataset`
     * alone, that whole dataset; with a `collection` alone, that collection of overturemaps.org.
     *
     * @throws IllegalArgumentException for an invalid region, or [SortMode.SIMILARITY] without text,
     *   before any request is made.
     * @throws AtgeoException when a getTiles call is refused by the server.
     */
    public suspend fun searchPlaces(
        region: Region,
        text: String? = null,
        dataset: String? = null,
        collection: String? = null,
        sort: SortMode = SortMode.FUSED,
    ): SearchResult {
        validateRegion(region)
        val query = normalize(text ?: "")
        require(sort != SortMode.SIMILARITY || query.isNotEmpty()) { "sort SIMILARITY requires text" }
        val searchedDataset = dataset ?: DEFAULT_DATASET
        val searchedCollection = collection ?: if (dataset == null) DEFAULT_COLLECTION else null
        return withContext(computeDispatcher) { search(region, query, sort, searchedDataset, searchedCollection) }
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
        // its own slots (null for a failed tile), so no counter is shared between threads.
        val slots = arrayOfNulls<List<TileRecord>>(urls.size)
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
        val fetched = slots.count { it != null }
        val failed = urls.size - fetched

        val prefix = collection?.let { "urn:atgeo:$dataset:$it:" }
        // dedupe picks a winner before the region filter runs, as the reference does (contract §4.6).
        val records = dedupe(slots.flatMap { it.orEmpty() })
            .filter { prefix == null || it.value["origin"].stringOrNull()?.startsWith(prefix) == true }
            .filter { inRegion(locate(it.value), region) }
        SearchResult(rank(records, region, query, sort), failed == 0, overview, SearchResult.TileCounts(fetched, failed))
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

    // The tile's records, or null if it failed. Checks the cache when the worker reaches the URL, as
    // the reference does, so recency is touched in the same order.
    private suspend fun loadTile(url: String): List<TileRecord>? {
        mutex.withLock { tiles.get(url) }?.let { return it.records }
        return try {
            val body = xrpc.get(url)
            val records = readTilePayload(parseBody(body))
            mutex.withLock { tiles.set(url, TileCache.Entry(records, body.size.toLong())) }
            records
        } catch (t: Throwable) {
            // Only the search's own cancellation propagates. A transport's withTimeout throws
            // TimeoutCancellationException, a CancellationException, and that is a failed tile.
            currentCoroutineContext().ensureActive()
            null
        }
    }

    /**
     * The place with this origin: from any cached tile without a request, else one getPlace call
     * sending the origin and nothing else (contract §9).
     *
     * @throws AtgeoException `PlaceNotFound` when the server has no such place.
     */
    public suspend fun getPlace(origin: String): PlaceRecord {
        // The scan walks every cached record, so it runs off the caller's thread.
        val cached = withContext(computeDispatcher) { mutex.withLock { tiles.find(origin) } }
        if (cached != null) return PlaceRecord(origin, cached.value, cached.importance)
        val (record, importance) = readPlace(xrpc.query("org.atgeo.getPlace", listOf("uri" to origin)))
        return PlaceRecord(origin, record, importance)
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
