# Conformance notes

Where this port reads input differently from the TypeScript reference, within the freedoms the [port contract](https://github.com/schuyler/atgeo-sdk-js/blob/main/docs/port-contract.md) grants (§5, §14). A compliant server never sends any of these; they matter only for malformed data.

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

`kotlinx.serialization`'s `Json.parseToJsonElement` accepts an unquoted literal such as `1.5f` or `yes` as a non-string primitive, where `JSON.parse` rejects the whole body. A body the reference refuses can therefore parse here.

## Ordering a NaN distance (contract §8)

The reference orders by subtracting (`a.distance - b.distance`), so a NaN distance compares as a tie with everything, which makes the comparator inconsistent and the resulting order engine-defined. Only out-of-range coordinates in place data (a latitude past ±90) can produce one. This port compares instead of subtracting, ties `-0` with `0` exactly as subtraction does, and sorts a NaN after every number, because Java's sort may throw on an inconsistent comparator.

Each entry is pinned by a test: the number table and the unquoted literal in `WireTest`, the NaN ordering in `RankingTest`.
