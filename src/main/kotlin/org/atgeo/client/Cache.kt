package org.atgeo.client

// Neither cache is thread-safe on its own; AtgeoClient guards both with its Mutex.

/** In-memory LRU of parsed tiles, capped by total body size in bytes (contract §10). */
internal class TileCache(private val budget: Long) {
    class Entry(val records: List<TileRecord>, val size: Long)

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
     * The record whose key is `key`, from the lowest tile URL holding one. Iterating entries does not
     * count as access, so recency is untouched (contract §9); get() would reorder mid-iteration.
     */
    fun find(key: String): TileRecord? {
        var bestUrl: String? = null
        var best: TileRecord? = null
        for ((url, entry) in tiles) {
            if (bestUrl != null && url >= bestUrl) continue
            val record = entry.records.firstOrNull { recordKey(it) == key } ?: continue
            bestUrl = url
            best = record
        }
        return best
    }

    /** URLs from least to most recently used, without touching recency. For tests. */
    fun urls(): List<String> = tiles.keys.toList()

    val totalSize: Long get() = total
}

/** Entries fresh while `clock() < set + ttlMillis` (contract §10). `clock` is epoch milliseconds. */
internal class TtlCache<T : Any>(private val ttlMillis: Long, private val clock: () -> Long) {
    private class Entry<T>(val value: T, val expires: Long)

    private val entries = HashMap<String, Entry<T>>()

    fun get(key: String): T? = entries[key]?.takeIf { clock() < it.expires }?.value

    fun set(key: String, value: T) {
        entries[key] = Entry(value, clock() + ttlMillis)
    }
}
