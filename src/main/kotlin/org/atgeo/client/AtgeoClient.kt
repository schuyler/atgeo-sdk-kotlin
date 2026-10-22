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

private const val DEFAULT_DATASET = "overturemaps.org"
private const val DEFAULT_COLLECTION = "place"
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
     * With no `dataset` and no `collection`, searches overturemaps.org / place; with a `dataset`
     * alone, that whole dataset; with a `collection` alone, that collection of overturemaps.org.
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
        mutex.withLock { parsed.replace(used.associate { (url, tile) -> url to (tile.entry to tile.records) }) }

        val prefix = collection?.let { "urn:atgeo:$dataset:$it:" }
        // dedupe picks a winner before the region filter runs, as the reference does (contract §4.6).
        val records = dedupe(used.flatMap { (_, tile) -> tile.records })
            .filter { prefix == null || it.value["origin"].stringOrNull()?.startsWith(prefix) == true }
            .filter { inRegion(locate(it.value), region) }
        val counts = SearchResult.TileCounts(fetched = used.size, failed = tileFailures.size)
        SearchResult(rank(records, region, query, sort), tileFailures.isEmpty(), overview, counts, tileFailures)
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
        class Used(val entry: TileCache.Entry, val records: List<TileRecord>) : TileOutcome

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
    // that is a failed tile. An Error is not caught, but for a StackOverflowError while decoding (decodeTile):
    // an OutOfMemoryError while parsing must not pass for one bad tile.
    private suspend fun loadTile(url: String): TileOutcome {
        if (!isHttpUrl(url)) {
            val error = MalformedURLException("tile URL is not an absolute http or https URL without credentials")
            return TileOutcome.Failed(TileFailure(url, TileFailure.Kind.REFUSED, null, error))
        }
        mutex.withLock { tiles.get(url)?.let { it to parsed.get(url, it) } }?.let { (entry, records) ->
            if (records != null) return TileOutcome.Used(entry, records)
            // A cached body parsed once already, so a deterministic parser cannot fail on it again.
            return try {
                TileOutcome.Used(entry, decodeTile(entry.body))
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                TileOutcome.Failed(TileFailure(url, TileFailure.Kind.DECODE, null, e))
            }
        }
        var kind = TileFailure.Kind.TRANSPORT
        var status: Int? = null
        val (body, records) = try {
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
        val entry = TileCache.Entry(body, records.mapNotNullTo(HashSet()) { recordKey(it) })
        mutex.withLock { tiles.set(url, entry) }
        return TileOutcome.Used(entry, records)
    }

    // The server's input caused a stack overflow in the decoder, so it is a decode failure (contract §4.4);
    // only that Error, never Error in general. kotlinx.serialization refuses deep nesting with a
    // SerializationException before it can overflow, so this is a guard for later versions.
    private fun decodeTile(body: ByteArray): List<TileRecord> = try {
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
            found?.let { (entry, records) ->
                (records ?: readTilePayload(parseBody(entry.body))).first { recordKey(it) == origin }
            }
        }
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
