package org.atgeo.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicReference

/**
 * Returned by [AtgeoClient.autocompleteByKeyword]. Make one per text field: "the previous call" belongs to the
 * autocompleter, so two fields sharing a client do not supersede each other. It holds only the previous call's
 * job and caches nothing.
 */
public class KeywordAutocomplete internal constructor(
    private val region: KeywordRegion?,
    private val search: suspend (keyword: String, bbox: String?) -> KeywordSearchResult,
) {
    private val previous = AtomicReference<Job?>(null)
    private val bbox: String? = region?.let { keywordBbox(it.west, it.south, it.east, it.north) }

    /**
     * Supersedes this autocompleter's previous call, then searches for `keyword`.
     *
     * **Every keyword that qualifies (contract §17) is sent to the server as given, prefixes included.** The
     * server keeps no log of it (garganorn's privacy design); an operator who chose to could record what it
     * receives. Debouncing reduces how many prefixes go out, and is the app's choice: this does not debounce.
     * A keyword the server would refuse as too short resolves an empty result without a request.
     * If the server refuses a keyword that qualified with `KeywordRequired`, the call also resolves empty, with
     * `disclosed.keyword` true and `disclosed.region` 1.0 when the autocompleter has a region, else null.
     *
     * A superseded call throws [CancellationException] although its caller is still active: treat a
     * `CancellationException` from this method as "superseded" and ignore the answer. The caller's own
     * cancellation also cancels the call, and surfaces as a `CancellationException` too.
     */
    public suspend fun searchByKeyword(keyword: String): KeywordSearchResult = coroutineScope {
        // A child of the caller's coroutine, so the caller's cancellation reaches the request (contract §11).
        val call = async { run(keyword) }
        previous.getAndSet(call)?.cancel(CancellationException("superseded by a later call"))
        try {
            call.await()
        } finally {
            previous.compareAndSet(call, null)
        }
    }

    private suspend fun run(keyword: String): KeywordSearchResult {
        if (!qualifies(keyword)) return KeywordSearchResult(emptyList(), emptyList(), Disclosure(false, null))
        return try {
            search(keyword, bbox)
        } catch (e: AtgeoException) {
            if (e.code != "KeywordRequired") throw e
            KeywordSearchResult(emptyList(), emptyList(), Disclosure(true, if (region != null) 1.0 else null))
        }
    }
}
