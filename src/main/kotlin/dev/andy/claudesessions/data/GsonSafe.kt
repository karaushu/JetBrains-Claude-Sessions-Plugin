package dev.andy.claudesessions.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Null-safe accessors for every external JSON format the plugin reads — transcripts, pid
 * files, hook events, `settings.json`, `~/.claude.json`.
 *
 * Gson's own `getAsJsonObject`/`getAsString` cast, so a JSON null throws, and these fields
 * are routinely null in the real payloads. The formats are Claude's, not ours, so tolerance
 * of an absent, null or wrongly-typed field is a property of this codec — one place to
 * harden when the CLI changes shape, instead of a private copy per reader.
 */

internal fun JsonObject.string(name: String): String? {
    val element = get(name) ?: return null
    if (element.isJsonNull || !element.isJsonPrimitive) return null
    return runCatching { element.asString }.getOrNull()?.takeIf { it.isNotBlank() }
}

internal fun JsonObject.int(name: String): Int? {
    val element = get(name) ?: return null
    if (element.isJsonNull || !element.isJsonPrimitive) return null
    return runCatching { element.asInt }.getOrNull()
}

internal fun JsonObject.long(name: String): Long? {
    val element = get(name) ?: return null
    if (element.isJsonNull || !element.isJsonPrimitive) return null
    return runCatching { element.asLong }.getOrNull()
}

internal fun JsonObject.boolean(name: String): Boolean? {
    val element = get(name) ?: return null
    if (element.isJsonNull || !element.isJsonPrimitive) return null
    return runCatching { element.asBoolean }.getOrNull()
}

internal fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject

internal fun JsonObject.array(name: String): JsonArray? = get(name) as? JsonArray
