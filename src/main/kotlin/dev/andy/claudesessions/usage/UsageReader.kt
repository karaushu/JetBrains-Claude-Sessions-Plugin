package dev.andy.claudesessions.usage

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists

/**
 * Reads the usage limits Claude caches in `~/.claude.json`.
 *
 * That file is the account-level config, not a per-session file, and Claude rewrites it
 * frequently — a read can land mid-write and yield truncated JSON, so parse failures are
 * expected and simply mean "try again next time".
 *
 * The cache is only refreshed while Claude Code or the desktop app is running, so the
 * caller must respect [UsageSnapshot.isStale] rather than presenting it as live.
 */
internal class UsageReader(
    private val file: Path = Path.of(System.getProperty("user.home"), ".claude.json"),
) {

    fun read(): UsageSnapshot? {
        if (!file.exists()) return null

        val text = runCatching { Files.readString(file, StandardCharsets.UTF_8) }.getOrNull() ?: return null
        val root = runCatching { JsonParser.parseString(text) as? JsonObject }.getOrNull() ?: return null

        return parse(root)
    }

    fun parse(root: JsonObject): UsageSnapshot? {
        val cached = root.obj("cachedUsageUtilization") ?: return null
        val fetchedAtMs = cached.long("fetchedAtMs") ?: return null

        val limitsArray = cached.obj("utilization")?.array("limits")
            ?: return UsageSnapshot(emptyList(), Instant.ofEpochMilli(fetchedAtMs))

        val limits = limitsArray.mapNotNull { element ->
            val limit = element as? JsonObject ?: return@mapNotNull null
            val kind = limit.string("kind") ?: return@mapNotNull null
            // A window with no percent tells us nothing; better absent than shown as 0%.
            val percent = limit.int("percent") ?: return@mapNotNull null

            UsageLimit(
                kind = kind,
                percent = percent,
                resetsAt = limit.string("resets_at")?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                },
                scopeModel = limit.obj("scope")?.obj("model")?.string("display_name"),
            )
        }

        return UsageSnapshot(limits, Instant.ofEpochMilli(fetchedAtMs))
    }

    // Gson's getAsJsonObject/getAsJsonArray cast, so a JSON null throws. These fields are
    // routinely null in the real payload — `scope` is null for every unscoped window — so
    // every access has to tolerate it.
    private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject

    private fun JsonObject.array(name: String): JsonArray? = get(name) as? JsonArray

    private fun JsonObject.string(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        return runCatching { element.asString }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.int(name: String): Int? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        return runCatching { element.asInt }.getOrNull()
    }

    private fun JsonObject.long(name: String): Long? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        return runCatching { element.asLong }.getOrNull()
    }
}
