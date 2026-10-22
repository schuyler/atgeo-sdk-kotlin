package org.atgeo.client

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

// Lenient readers. A primitive is reached with `as? JsonPrimitive`, never
// `.jsonPrimitive` (which throws on an object or array), and its kind is
// checked before it is read: longOrNull and doubleOrNull parse string content.

/** The string a JSON string holds; null for anything else. */
internal fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The value of a JSON number; null for a string, boolean, null, object or array. */
internal fun JsonElement?.numberOrNull(): Double? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
