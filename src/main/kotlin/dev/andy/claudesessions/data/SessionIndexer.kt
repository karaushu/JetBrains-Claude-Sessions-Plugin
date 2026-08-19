package dev.andy.claudesessions.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import dev.andy.claudesessions.model.SessionSummary
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
 * length check.
 *
 * Results are cached on (size, mtime). Transcripts are append-only, so a grown file is
 * scanned incrementally from the previous scan's offset — a live multi-megabyte session
 * costs only its appended bytes per rescan. A file that shrank was rewritten (compaction),
 * so it is rescanned from the start.
 */
internal class SessionIndexer(
    /**
     * A live session appends constantly, so (size, mtime) alone would invalidate the cache
     * on every tick. Titles change rarely, and last-activity comes from a cheap stat, so a
     * re-scan of a changing file is rate-limited. Injectable so tests need not wait.
     */
    private val minRescanIntervalNanos: Long = TimeUnit.SECONDS.toNanos(30),
) {

    /** The running fold over a transcript's records; resumable, so appends merge in. */
    private data class ScanState(
        var cwd: String? = null,
        var gitBranch: String? = null,
        var startedAt: Instant? = null,
        var parsedSessionId: String? = null,
        // Index records accrete throughout the file, so the LAST occurrence of each wins.
        var worktreeName: String? = null,
        var originalCwd: String? = null,
        var customTitle: String? = null,
        var aiTitle: String? = null,
        var lastPrompt: String? = null,
        var slug: String? = null,
    )

    private class Cached(
        val size: Long,
        val mtime: Long,
        val scannedAtNanos: Long,
        val summary: SessionSummary,
        val state: ScanState,
        /** Offset just past the last complete line consumed; the next scan resumes here. */
        val consumedBytes: Long,
    )

    private val cache = ConcurrentHashMap<Path, Cached>()

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

        // A grown file only appended, so resume the fold; a shrunken one was rewritten.
        val resumable = previous?.takeIf { size >= it.consumedBytes }
        val state = resumable?.state?.copy() ?: ScanState()
        val startOffset = resumable?.consumedBytes ?: 0L

        val consumed = runCatching { scanFrom(file, startOffset, state) }
            .onFailure { thisLogger().warn("Failed to index $file", it) }
            .getOrNull() ?: return previous?.summary
        val summary = buildSummary(file, state, size, mtime) ?: return previous?.summary

        cache[file] = Cached(size, mtime, System.nanoTime(), summary, state, consumed)
        return summary
    }

    /**
     * Folds every complete line from [startOffset] into [state] and returns the offset just
     * past the last newline consumed. Bytes after the last newline — a record the CLI is
     * still writing — are left for the next scan, so a torn record is never half-parsed.
     */
    private fun scanFrom(file: Path, startOffset: Long, state: ScanState): Long {
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            channel.position(startOffset)
            val buffer = ByteBuffer.allocate(CHUNK_BYTES)
            val pending = ByteArrayOutputStream()
            // Bytes of the current line beyond the keep-cap: counted for offset accounting,
            // never buffered — an inline base64 image must not balloon the heap.
            var pendingSkipped = 0L
            var isFirstFileLine = startOffset == 0L
            var consumed = startOffset

            // UTF-8 is at most 4 bytes per char, so a line kept in full up to this byte cap
            // can never be rejected by the char cap alone — the caps agree.
            fun stash(bytes: ByteArray, from: Int, until: Int) {
                val length = until - from
                if (length <= 0) return
                val cap = (if (isFirstFileLine) firstLineParseCap else maxParsedLineLength) * MAX_UTF8_BYTES_PER_CHAR
                val room = (cap - pending.size()).coerceIn(0, length)
                if (room > 0) pending.write(bytes, from, room)
                pendingSkipped += length - room
            }

            while (channel.read(buffer) > 0) {
                val bytes = buffer.array()
                val limit = buffer.position()
                var lineStart = 0
                for (i in 0 until limit) {
                    if (bytes[i] != NEWLINE) continue
                    stash(bytes, lineStart, i)
                    if (pendingSkipped == 0L) {
                        fold(pending.toString(StandardCharsets.UTF_8).trimEnd('\r'), state, isFirstFileLine)
                    }
                    consumed += pending.size() + pendingSkipped + 1
                    pending.reset()
                    pendingSkipped = 0
                    isFirstFileLine = false
                    lineStart = i + 1
                }
                stash(bytes, lineStart, limit)
                buffer.clear()
            }
            return consumed
        }
    }

    private fun fold(line: String, state: ScanState, isFirstFileLine: Boolean) {
        if (line.isEmpty()) return

        // cwd and gitBranch live on envelope records, which can exceed the length cap. The
        // first record is the cheapest reliable source for both, so parse it regardless
        // (bounded, to stay safe against a pathological line).
        val withinCap = line.length <= maxParsedLineLength ||
            (isFirstFileLine && line.length <= firstLineParseCap)
        if (!withinCap) return

        val obj = parseOrNull(line) ?: return

        when (obj.string("type")) {
            "custom-title" -> obj.string("customTitle")?.let { state.customTitle = it }
            "ai-title" -> obj.string("aiTitle")?.let { state.aiTitle = it }
            "last-prompt" -> obj.string("lastPrompt")?.let { state.lastPrompt = it }
            // A relocated session reports a cwd that disagrees with its directory name.
            "relocated" -> obj.string("relocatedCwd")?.let { state.cwd = it }
            "worktree-state" -> (obj.get("worktreeSession") as? JsonObject)?.let { w ->
                w.string("worktreeName")?.let { state.worktreeName = it }
                w.string("originalCwd")?.let { state.originalCwd = it }
            }
        }

        if (state.cwd == null) state.cwd = obj.string("cwd")
        if (state.gitBranch == null) state.gitBranch = obj.string("gitBranch")
        if (state.slug == null) state.slug = obj.string("slug")
        if (state.parsedSessionId == null) state.parsedSessionId = obj.string("sessionId")
        if (state.startedAt == null) {
            state.startedAt = obj.string("timestamp")?.let { ts ->
                runCatching { Instant.parse(ts) }.getOrNull()
            }
        }
    }

    private fun buildSummary(file: Path, state: ScanState, size: Long, mtime: Long): SessionSummary? {
        // The filename is the session id in every observed case; the field is a fallback.
        val sessionId = file.nameWithoutExtension.takeIf { it.isNotBlank() }
            ?: state.parsedSessionId
            ?: return null

        return SessionSummary(
            sessionId = sessionId,
            transcript = file,
            cwd = state.cwd,
            gitBranch = state.gitBranch,
            title = state.customTitle
                ?: state.aiTitle
                ?: state.slug?.let(::humanizeSlug)
                ?: state.lastPrompt,
            startedAt = state.startedAt,
            // Transcripts are append-only, so mtime is exactly the last activity.
            lastActivity = Instant.ofEpochMilli(mtime),
            sizeBytes = size,
            worktreeName = state.worktreeName,
            originalCwd = state.originalCwd,
        )
    }

    private fun parseOrNull(line: String): JsonObject? =
        runCatching { JsonParser.parseString(line) as? JsonObject }.getOrNull()

    /** `i-want-a-plugin-serialized-badger` reads better as `I want a plugin serialized badger`. */
    private fun humanizeSlug(slug: String): String =
        slug.replace('-', ' ').replaceFirstChar { it.uppercase() }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
        const val NEWLINE = '\n'.code.toByte()
        const val MAX_UTF8_BYTES_PER_CHAR = 4
    }
}
