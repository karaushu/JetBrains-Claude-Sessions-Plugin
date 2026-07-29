package dev.andy.claudesessions.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import dev.andy.claudesessions.model.SessionSummary
import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * Builds lightweight summaries of `~/.claude/projects/<dir>/<sessionId>.jsonl`.
 *
 * Transcripts reach 6.5 MB with base64 images inline, so nothing here parses a whole
 * file. Only short records are handed to the JSON parser; longer lines are skipped on a
 * length check. Results are cached on (size, mtime), which is sound because transcripts
 * are append-only.
 */
internal class SessionIndexer {

    private class Cached(
        val size: Long,
        val mtime: Long,
        val scannedAtNanos: Long,
        val summary: SessionSummary,
    )

    private val cache = ConcurrentHashMap<Path, Cached>()

    /**
     * A live session appends constantly, so (size, mtime) alone would invalidate the cache
     * on every tick and re-read multi-megabyte files. Titles change rarely, and last-activity
     * comes from a cheap stat, so a full re-scan of a changing file is rate-limited.
     */
    private val minRescanIntervalNanos = TimeUnit.SECONDS.toNanos(30)

    /** Longest line we are willing to hand to the JSON parser. */
    private val maxParsedLineLength = 8 * 1024

    /** The first record is worth parsing even when it is large; still bounded. */
    private val firstLineParseCap = 512 * 1024

    /** Summarises every transcript in [directory]. Empty if it does not exist. */
    fun indexDirectory(directory: Path): List<SessionSummary> {
        if (!directory.isDirectory()) return emptyList()

        val files = runCatching {
            directory.listDirectoryEntries().filter { it.extension == "jsonl" }
        }.getOrElse { emptyList() }

        return files.mapNotNull { summarise(it) }
    }

    fun summarise(file: Path): SessionSummary? {
        val size = runCatching { file.fileSize() }.getOrNull() ?: return null
        val mtime = runCatching { file.getLastModifiedTime().toMillis() }.getOrNull() ?: return null

        val previous = cache[file]
        if (previous != null) {
            val unchanged = previous.size == size && previous.mtime == mtime
            val scannedRecently =
                System.nanoTime() - previous.scannedAtNanos < minRescanIntervalNanos
            if (unchanged || scannedRecently) {
                // Freshen the cheap fields; the parsed ones are still good enough.
                return previous.summary.copy(
                    lastActivity = Instant.ofEpochMilli(mtime),
                    sizeBytes = size,
                )
            }
        }

        val summary = runCatching { scan(file, size, mtime) }
            .onFailure { thisLogger().warn("Failed to index $file", it) }
            .getOrNull() ?: return previous?.summary

        cache[file] = Cached(size, mtime, System.nanoTime(), summary)
        return summary
    }

    private fun scan(file: Path, size: Long, mtime: Long): SessionSummary? {
        var cwd: String? = null
        var gitBranch: String? = null
        var startedAt: Instant? = null
        var parsedSessionId: String? = null

        // Index records are rewritten in place throughout the file, so the LAST
        // occurrence of each wins.
        var worktreeName: String? = null
        var originalCwd: String? = null
        var customTitle: String? = null
        var aiTitle: String? = null
        var lastPrompt: String? = null
        var slug: String? = null

        val reader: BufferedReader = runCatching {
            Files.newBufferedReader(file, StandardCharsets.UTF_8)
        }.getOrNull() ?: return null

        reader.use {
            var isFirstLine = true
            while (true) {
                val line = it.readLine() ?: break
                if (line.isEmpty()) continue

                // cwd and gitBranch live on envelope records, which can exceed the length
                // cap. The first record is the cheapest reliable source for both, so parse
                // it regardless (bounded, to stay safe against a pathological line).
                val withinCap = line.length <= maxParsedLineLength ||
                    (isFirstLine && line.length <= firstLineParseCap)
                isFirstLine = false
                if (!withinCap) continue

                val obj = parseOrNull(line) ?: continue

                when (obj.string("type")) {
                    "custom-title" -> obj.string("customTitle")?.let { v -> customTitle = v }
                    "ai-title" -> obj.string("aiTitle")?.let { v -> aiTitle = v }
                    "last-prompt" -> obj.string("lastPrompt")?.let { v -> lastPrompt = v }
                    // A relocated session reports a cwd that disagrees with its directory name.
                    "relocated" -> obj.string("relocatedCwd")?.let { v -> cwd = v }
                    "worktree-state" -> (obj.get("worktreeSession") as? JsonObject)?.let { w ->
                        w.string("worktreeName")?.let { v -> worktreeName = v }
                        w.string("originalCwd")?.let { v -> originalCwd = v }
                    }
                }

                if (cwd == null) cwd = obj.string("cwd")
                if (gitBranch == null) gitBranch = obj.string("gitBranch")
                if (slug == null) slug = obj.string("slug")
                if (parsedSessionId == null) parsedSessionId = obj.string("sessionId")
                if (startedAt == null) {
                    startedAt = obj.string("timestamp")?.let { ts ->
                        runCatching { Instant.parse(ts) }.getOrNull()
                    }
                }
            }
        }

        // The filename is the session id in every observed case; the field is a fallback.
        val sessionId = file.nameWithoutExtension.takeIf { it.isNotBlank() }
            ?: parsedSessionId
            ?: return null

        return SessionSummary(
            sessionId = sessionId,
            transcript = file,
            cwd = cwd,
            gitBranch = gitBranch,
            title = customTitle ?: aiTitle ?: slug?.let(::humanizeSlug) ?: lastPrompt,
            startedAt = startedAt,
            // Transcripts are append-only, so mtime is exactly the last activity.
            lastActivity = Instant.ofEpochMilli(mtime),
            sizeBytes = size,
            worktreeName = worktreeName,
            originalCwd = originalCwd,
        )
    }

    private fun parseOrNull(line: String): JsonObject? =
        runCatching { JsonParser.parseString(line) as? JsonObject }.getOrNull()

    private fun JsonObject.string(name: String): String? {
        val element = get(name) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive) return null
        return runCatching { element.asString }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** `i-want-a-plugin-serialized-badger` reads better as `I want a plugin serialized badger`. */
    private fun humanizeSlug(slug: String): String =
        slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
}
