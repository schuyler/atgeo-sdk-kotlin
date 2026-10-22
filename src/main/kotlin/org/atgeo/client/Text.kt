package org.atgeo.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

// JavaScript's \s, exactly (contract §7). Java's \s, UNICODE_CHARACTER_CLASS
// and Kotlin's trim() each match a different set.
private val WHITESPACE: Set<Int> = (
    listOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680) +
        (0x2000..0x200A) + listOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF)
    ).toSet()

/**
 * Lowercase (locale-independent full case mapping), NFKD, lowercase again, drop
 * nonspacing marks, collapse whitespace runs to one space and trim (contract §7).
 */
internal fun normalize(s: String): String {
    // lowercase() is toLowerCase(Locale.ROOT); the default locale would turn I into ı on a Turkish device.
    // The second pass catches capitals NFKD makes from characters with no lowercase of their own (ℌ, 𝐇);
    // on text that is already lowercase it changes nothing.
    val folded = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).lowercase()
    val out = StringBuilder(folded.length)
    var pendingSpace = false
    var i = 0
    while (i < folded.length) {
        val cp = folded.codePointAt(i)
        i += Character.charCount(cp)
        if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) continue
        // A run becomes one space only when something follows it, which also trims both ends.
        if (cp in WHITESPACE) {
            pendingSpace = out.isNotEmpty()
            continue
        }
        if (pendingSpace) {
            out.append(' ')
            pendingSpace = false
        }
        out.appendCodePoint(cp)
    }
    return out.toString()
}

// Over UTF-16 code units, as the reference does: s[i] and s.length already are.
private fun jaro(a: String, b: String): Double {
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val reach = max(0, max(a.length, b.length) / 2 - 1)
    val aMatched = BooleanArray(a.length)
    val bMatched = BooleanArray(b.length)
    var matches = 0
    for (i in a.indices) {
        val hi = min(b.length - 1, i + reach)
        // Runs zero times when the start passes the end (starbux against bar).
        var j = max(0, i - reach)
        while (j <= hi) {
            if (!bMatched[j] && a[i] == b[j]) {
                aMatched[i] = true
                bMatched[j] = true
                matches++
                break
            }
            j++
        }
    }
    if (matches == 0) return 0.0
    var transpositions = 0
    var j = 0
    for (i in a.indices) {
        if (!aMatched[i]) continue
        while (!bMatched[j]) j++
        if (a[i] != b[j++]) transpositions++
    }
    val m = matches.toDouble()
    return (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3
}

internal fun jaroWinkler(a: String, b: String): Double {
    val sim = jaro(a, b)
    var prefix = 0
    while (prefix < 4 && prefix < a.length && prefix < b.length && a[prefix] == b[prefix]) prefix++
    return sim + prefix * 0.1 * (1 - sim)
}

private const val MAX_QUERY_TOKENS = 12

private fun scoreName(query: String, tokens: List<String>, name: String): Double {
    val nameTokens = name.split(" ")
    // Summed left to right from zero, as the reference does, so scores agree to the last bit.
    var sum = 0.0
    for (t in tokens) sum += nameTokens.maxOf { jaroWinkler(t, it) }
    return 0.5 * jaroWinkler(query, name) + 0.5 * (sum / tokens.size)
}

/** Best score of `query` (already normalized, non-empty) against the place's name and variant names. */
internal fun textScore(query: String, value: JsonObject): Double {
    val tokens = query.split(" ").take(MAX_QUERY_TOKENS)
    val variants = (value["variants"] as? JsonArray).orEmpty()
    val names = (listOf(value["name"]) + variants.map { (it as? JsonObject)?.get("name") })
        .mapNotNull { it.stringOrNull() }
        .map(::normalize)
        .filter { it.isNotEmpty() }
    var best = 0.0
    for (n in names) best = max(best, scoreName(query, tokens, n))
    return best
}
