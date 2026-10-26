# Conformance notes

This port follows the port contract at atgeo-sdk-js `2710a14ba7d9485ddb6c00975f396e95389ac419` (fixtures and lexicons pinned in the `SOURCE` files under `src/test/resources/fixtures/`, which CI checks byte for byte). Where this port reads input differently from the TypeScript reference, within the freedoms the [port contract](https://github.com/schuyler/atgeo-sdk-js/blob/main/docs/port-contract.md) grants (§5, §14). A compliant server never sends any of these; they matter only for malformed data.

## Number parsing (contract §5)

Coordinates that arrive as strings are parsed with Kotlin's `String.toDoubleOrNull()` (the `java.lang.Double.parseDouble` grammar), where the reference uses `parseFloat`. Non-finite results are rejected in both.

| Input | Reference (`parseFloat`) | This port (`toDoubleOrNull`) |
|---|---|---|
| ` 1.5 ` (surrounding ASCII whitespace) | 1.5 | 1.5 |
| `1.5f`, `1.5d` | 1.5 | 1.5 |
| `0x1p3` (hex float) | 0 | 8.0 |
| `1e` | 1 | no location |
| `1.5abc` | 1.5 | no location |
| `1_000` | 1 | no location |
| `+1.5`, `.5`, `1.`, `-0` | same in both | same in both |
| `Infinity`, `NaN` | non-finite, skipped | non-finite, skipped |

## JSON parsing

`kotlinx.serialization`'s `Json.parseToJsonElement` accepts an unquoted literal such as `1.5f` or `yes` as a non-string primitive, where `JSON.parse` rejects the whole body. A body the reference refuses can therefore parse here. Inside an object such a value is just a wrong-typed field and reads as absent; a whole body that is one such token (`<html>`, `yes`) parses as a lone primitive, which every reader rejects for not being an object, so the call fails with `AtgeoDecodingException` exactly as for a body that does not parse.

## Final sigma (contract §7)

Normalization lowercases with `String.lowercase()`, which is `toLowerCase(Locale.ROOT)`. The JDK decides whether a capital sigma is word-final by a different rule from V8's (which is Unicode's), so where a digit or a combining mark sits next to the sigma they can disagree:

| Input | Reference (V8) | This port (JDK) |
|---|---|---|
| `Σ1Σ` | `σ1σ` | `σ1ς` |
| `Σ𝚺` + U+0345 | `σς` + U+0345 | `σσ` + U+0345 |

Step 3's second lowercase carries the same difference to the mathematical capital sigmas (U+1D6BA `𝚺` and its four other styles), which NFKD turns into Σ. Ordinary words, the abbreviations in the fixture and `𝚺𝚨𝚺` agree. A name would have to put a capital sigma next to a digit or a combining mark to tell the two apart, and then they differ by one letter, σ against ς, which moves that name's similarity score slightly.

## Refused tile URLs (contract §4.4)

Before any transport sees a tile URL, the app's included, the client checks that it is an absolute `http` or `https` URL with a host and no credentials, as the Fetch standard's `Request` does in the reference; a URL that is not is a failed tile and no request is made. The check reads the URL as WHATWG URL parsing would where that is cheap: leading and trailing spaces and C0 controls are ignored, tab, LF and CR are ignored anywhere, the scheme is matched without regard to ASCII case, and the authority ends at `/`, `\`, `?` or `#`. The URL the transport gets is the one the tile list gave, unchanged. Where the check and `fetch` differ:

| Tile URL | Reference (`fetch`) | This port |
|---|---|---|
| `https://@h/x`, `https://:@h/x` (empty userinfo, no credentials) | the userinfo dropped, the URL fetched | a failed tile (`REFUSED`), as in the Python and Rust ports and as §4.4 allows: any `@` before the host is refused |
| `https://h\@x/y` | host `h`, path `/@x/y`, fetched | a failed tile: the `@` is looked for up to the first `/`, `?` or `#`, because `java.net.URL` does not end the authority at a backslash and reads userinfo `h\` at host `x` |
| `https:h/x`, `https:/h/x`, `https:///h/x`, and backslash forms such as `https:\\h/x`, `https:/\h/x`, `https:\/h/x`, `https://\h/x` and other mixes of `/` and `\` | fetched from host `h`: WHATWG reads `\` as `/` and ignores missing or extra slashes after a special scheme | a failed tile: the check wants exactly `://`, as the Rust port's does, and an authority that starts with `\` has no host |
| `https://h%41/x` | host `ha` once percent-decoded, fetched | a failed tile: `%` is refused in a host along with WHATWG's other forbidden host code points |

The check also refuses what WHATWG's parser rejects and `java.net.URL` might not: a port that is not ASCII digits or is above 65535 (`java.net.URL` would fetch `http://127.0.0.1:+80/x`), a host with a forbidden host code point (a space, a control character, `<`, `>`, `^`, `|`, `[`, `]` and the like), and a bracketed IPv6 host that is unclosed, holds anything but hex digits, `:` and `.`, or is followed by anything but a port. Other URLs WHATWG would reject, such as an IPv4 address out of range (`http://1.2.3.256/x`), pass the check and are left to the transport; with the default transport they fail there, as `TRANSPORT`.

A URL the check accepts can still fail in the transport where WHATWG would have cleaned it up: the default transport does not drop a tab or newline inside a URL, or read a backslash as `/`, so such a tile fails there as a transport error. `java.net.URL` trims the same characters at the ends that WHATWG does. Pinned by `TransportTest`.

## Failed tiles (contract §4.4)

`SearchResult.tileFailures` is the contract's `tileFailures`, and `TileFailure.Kind`'s `REFUSED`, `STATUS`, `DECODE` and `TRANSPORT` are its `refused`, `status`, `decode` and `transport`. `error` is a `MalformedURLException` for `REFUSED`, the `AtgeoException` for `STATUS`, whatever the parser threw (normally an `AtgeoDecodingException`) for `DECODE`, and what the transport threw for `TRANSPORT`, with one exception: a `CancellationException` a transport throws while the search is still active, such as its own `withTimeout`'s, is reported as the `SocketTimeoutException` or `IOException` the client turns it into everywhere else (README, Errors), with the original as its cause. A `StackOverflowError` while decoding a tile is `DECODE` too, as an `AtgeoDecodingException` with the overflow as its cause; no other `Error` is caught, so an `OutOfMemoryError` fails the search. In practice `kotlinx.serialization` refuses deeply nested JSON with a `SerializationException` before its stack can overflow, so a deep body is an ordinary `DECODE`. A tile whose cached body fails to parse again would be `DECODE`; no test can reach that path, since the body parsed once already. Inserting a tile in the cache is outside the guarded part, so an exception there fails the search. The port logs nothing: neither the JVM nor Android has a standard facility that serves both without a dependency.

## Ordering a NaN distance (contract §8)

The reference orders by subtracting (`a.distance - b.distance`), so a NaN distance compares as a tie with everything, which makes the comparator inconsistent and the resulting order engine-defined. Only out-of-range coordinates in place data (a latitude past ±90) can produce one. This port compares instead of subtracting, ties `-0` with `0` exactly as subtraction does, and sorts a NaN after every number, because Java's sort may throw on an inconsistent comparator.

The number table and the unquoted literal are pinned by tests in `WireTest`, the NaN ordering in `RankingTest`. The final-sigma cases are not: they are the JDK's behaviour, not a choice this port makes.

## A null dataset (contract §4.1)

`dataset` is a non-null `String`, which is the contract's "non-optional type": a search without one does not compile. The one way past it is a caller that passes `null` (Java, reflection). The Kotlin compiler emits no null check for the parameters of a `suspend` function, so `searchPlaces` checks for itself and throws `IllegalArgumentException` mentioning `dataset`, as its first statement, before the region is validated and before any request. Pinned by `ClientTest` (`DatasetsAndCollections`), which calls the method reflectively.

## Attribution (contract §4.10, §9)

`Attribution` copies `licenses` into an unmodifiable list, and the lists `searchPlaces` and `getPlace` return are unmodifiable copies, so a cast to `MutableList` throws instead of reaching the cache (the reference copies instead of freezing; contract §9 allows either). `SearchHit.collection` is a nullable `CollectionKey`, so the contract's "always present, an object or null" holds by type. The sort uses `String.compareTo`, which is UTF-16 code unit order, as §4.3 asks; `AttributionScenariosTest` pins it above U+FFFF, which the fixtures do not.

## What the tests run

The port runs every vendored fixture: `normalize.json`, `jaro-winkler.json`, `ranking-scenarios.json` (`RankingTest`, all 19 scenarios, with each scenario's dataset and collection; `"overview": true` makes the fake server answer `getTiles` with an overview), `match-tiers.json` (`MatchTiersTest`, all 36 cases, calling the internal `matchTier` on the normalized query and a place built from `name` and `variants`), `lenient-readers.json` (`LenientReadersTest`, all 104 cases, compared as JSON) and `attribution-scenarios.json` (`AttributionScenariosTest`, a fresh client per scenario against a raw server, with the default error body for a tile that has a status and no body). The same test class reruns every multi-tile scenario with the highest tile URL answering first (virtual-time delays) to pin that attribution and hit collections follow URL order, not arrival order (contract §13). The fake server does not validate against the lexicons; it checks parameter names, the bbox format and its own response fields, as §13 allows where no validator exists.
