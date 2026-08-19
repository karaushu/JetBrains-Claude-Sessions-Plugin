package dev.andy.claudesessions.usage

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.andy.claudesessions.data.array
import dev.andy.claudesessions.data.int
import dev.andy.claudesessions.data.long
import dev.andy.claudesessions.data.obj
import dev.andy.claudesessions.data.string
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant

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

    /** The file identity a snapshot was parsed from, so an unchanged file is not re-parsed. */
    private data class Stamp(val size: Long, val mtime: FileTime)

    private val lock = Any()
    private var cachedStamp: Stamp? = null
    private var cachedSnapshot: UsageSnapshot? = null

    fun read(): UsageSnapshot? {
        // The file is the whole account config and grows to megabytes; a stat is enough to
        // notice it has not changed since the last parse.
        val stamp = stat() ?: run {
            synchronized(lock) {
                cachedStamp = null
                cachedSnapshot = null
            }
            return null
        }
        synchronized(lock) {
            if (stamp == cachedStamp) return cachedSnapshot
        }

        val text = runCatching { Files.readString(file, StandardCharsets.UTF_8) }.getOrNull() ?: return null
        // A read landing mid-write fails to parse; leave the cache alone so the next call retries.
        val element = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return null
        val snapshot = (element as? JsonObject)?.let { parse(it) }
        synchronized(lock) {
            cachedStamp = stamp
            cachedSnapshot = snapshot
        }
        return snapshot
    }

    private fun stat(): Stamp? = runCatching {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        Stamp(attributes.size(), attributes.lastModifiedTime())
    }.getOrNull()

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

}
