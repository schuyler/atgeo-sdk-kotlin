package org.atgeo.client

// None of these caches is thread-safe on its own; AtgeoClient guards them with its Mutex.

/**
 * In-memory LRU of tiles, capped by total body size in bytes (contract §10). It holds each tile's body
 * as served, not its parsed records: parsed, a tile takes 5 to 7 times its body size on the heap (ART
 * and HotSpot), so a full 50 MiB budget of parsed tiles would be 250 to 350 MB. [ParsedTiles] keeps the
 * parsed form of the latest search's tiles only.
 */
internal class TileCache(private val budget: Long) {
    /** A tile's body, and the keys (contract §4.6) of its usable records for the getPlace scan. */
    class Entry(val body: ByteArray, val keys: Set<String>, val size: Long = body.size.toLong())

    // Access order: get() moves an entry to the end, so the eldest is least recently used.
    private val tiles = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var total = 0L

    /** Marks the tile most recently used. */
    fun get(url: String): Entry? = tiles[url]

    fun set(url: String, entry: Entry) {
        tiles.remove(url)?.let { total -= it.size }
        tiles[url] = entry
        total += entry.size
        // removeEldestEntry can evict only one entry per insert; a large tile may need to evict
        // several, itself included when it alone exceeds the budget.
        val eldest = tiles.entries.iterator()
        while (total > budget && eldest.hasNext()) {
            total -= eldest.next().value.size
            eldest.remove()
        }
    }

    /**
     * The lowest tile URL holding a record whose key is `key`, with its entry. Iterating entries does not
     * count as access, so recency is untouched (contract §9); get() would reorder mid-iteration.
     */
    fun find(key: String): Pair<String, Entry>? {
        var best: Pair<String, Entry>? = null
        for ((url, entry) in tiles) {
            if (best != null && url >= best.first) continue
            if (key in entry.keys) best = url to entry
        }
        return best
    }

    /** URLs from least to most recently used, without touching recency. For tests. */
    fun urls(): List<String> = tiles.keys.toList()

    val totalSize: Long get() = total
}

/**
 * The parsed records of the latest search's tiles, so that a search over the same tiles (the same
 * region with new text, as a user types) parses nothing. A memo only: a tile is used only while
 * [TileCache] still holds the same entry, so what is fetched, and when, is exactly as without it.
 */
internal class ParsedTiles {
    /** A tile's usable records and its attribution (empty when it has none), as parsed from the entry's body. */
    class Parsed(val entry: TileCache.Entry, val records: List<TileRecord>, val attribution: List<Attribution>)

    private var tiles: Map<String, Parsed> = emptyMap()

    fun get(url: String, entry: TileCache.Entry): Parsed? = tiles[url]?.takeIf { it.entry === entry }

    /** Replaces the memo with one search's tiles. */
    fun replace(tiles: Map<String, Parsed>) {
        this.tiles = tiles
    }
}

/** Entries fresh while `clock() < set + ttlMillis` (contract §10). `clock` is epoch milliseconds. */
internal class TtlCache<T : Any>(private val ttlMillis: Long, private val clock: () -> Long) {
    private class Entry<T>(val value: T, val expires: Long)

    private val entries = HashMap<String, Entry<T>>()

    fun get(key: String): T? = entries[key]?.takeIf { clock() < it.expires }?.value

    fun set(key: String, value: T) {
        val now = clock()
        // Every new search box is a new key, so without pruning a long-lived client only grows (contract §14).
        entries.values.removeAll { now >= it.expires }
        entries[key] = Entry(value, now + ttlMillis)
    }

    /** Entries held, fresh or not. For tests. */
    val size: Int get() = entries.size
}
