package org.atgeo.client

import java.util.Collections
import kotlinx.serialization.json.JsonObject

// Attribution and hit collections (contract §4.7, §4.10, §8).

internal fun collectionPrefix(dataset: String, collection: String) = "urn:atgeo:$dataset:$collection:"

/** The test of §4.7, shared with the hit's collection: on `value.origin` only, never the uri fallback. */
internal fun inCollection(value: JsonObject, prefix: String): Boolean = value["origin"].stringOrNull()?.startsWith(prefix) == true

/** The first entry, in served order, whose collection the origin belongs to, as a new key; null when none does. */
internal fun collectionOf(value: JsonObject, attribution: List<Attribution>): CollectionKey? {
    for (entry in attribution) {
        if (inCollection(value, collectionPrefix(entry.dataset, entry.collection))) return CollectionKey(entry.dataset, entry.collection)
    }
    return null
}

/** A list nobody can change, over a private copy. */
internal fun <T> frozen(list: List<T>): List<T> = Collections.unmodifiableList(ArrayList(list))

/**
 * The union of the lists in the order given: the first entry for each (dataset, collection) wins, no fields merged,
 * the pair compared as a tuple. Sorted by dataset then collection in UTF-16 code unit order (`String.compareTo`).
 */
internal fun mergeAttribution(lists: List<List<Attribution>>): List<Attribution> {
    val seen = HashSet<Pair<String, String>>()
    val merged = ArrayList<Attribution>()
    for (list in lists) for (e in list) if (seen.add(e.dataset to e.collection)) merged.add(e)
    merged.sortWith(compareBy<Attribution> { it.dataset }.thenBy { it.collection })
    return frozen(merged)
}
