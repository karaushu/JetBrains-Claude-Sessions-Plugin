package dev.andy.claudesessions.hooks

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import dev.andy.claudesessions.data.ClaudePaths
import dev.andy.claudesessions.data.FileTail
import dev.andy.claudesessions.data.boolean
import dev.andy.claudesessions.data.string
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime

/**
 * Tails the append-only log our hooks write.
 *
 * Each hook invocation appends its raw stdin JSON as one line. Reading is a single `stat`
 * plus a read of whatever is new, so it is cheap enough to check several times a second.
 *
 * A very long payload could in principle interleave with a concurrent append and produce a
 * torn line. That is tolerated rather than prevented: a torn line simply fails to parse and
 * is skipped, and polling `~/.claude/sessions` remains the authoritative source.
 */
internal class HookEventLog(private val file: Path = ClaudePaths.hookEventLog) {

    private var offset: Long = 0

    /** Keeps the log from growing without bound; truncated only after being read. */
    private val maxBytes = 512 * 1024L

    /** Past this, the quiet-period requirement is waived — the cap must actually cap. */
    private val hardMaxBytes = 4 * 1024 * 1024L

    /**
     * Returns events appended since the last call. Empty when nothing changed, which is
     * the overwhelmingly common case.
     */
    fun readNew(): List<HookEvent> {
        if (!file.exists()) {
            offset = 0
            return emptyList()
        }

        val size = runCatching { file.fileSize() }.getOrNull() ?: return emptyList()

        // Rotated or truncated behind our back: start over rather than read garbage.
        if (size < offset) offset = 0
        if (size == offset) return emptyList()

        val text = runCatching {
            String(FileTail.readBytes(file, offset, size), StandardCharsets.UTF_8)
        }
            .onFailure { thisLogger().debug("Cannot read hook event log", it) }
            .getOrNull() ?: return emptyList()

        offset = size

        val events = text.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull(::parse)
            .toList()

        if (size > maxBytes) truncate(force = size > hardMaxBytes)
        return events
    }

    private fun parse(line: String): HookEvent? {
        val obj = runCatching { JsonParser.parseString(line) as? JsonObject }.getOrNull() ?: return null
        val sessionId = obj.string("session_id") ?: return null
        val eventName = obj.string("hook_event_name") ?: return null
        return HookEvent(
            sessionId = sessionId,
            eventName = eventName,
            notificationType = obj.string("notification_type"),
            cwd = obj.string("cwd"),
            message = obj.string("message"),
            sessionTitle = obj.string("session_title"),
            lastAssistantMessage = obj.string("last_assistant_message"),
            transcriptPath = obj.string("transcript_path"),
            stopHookActive = obj.boolean("stop_hook_active") == true,
        )
    }

    /**
     * Moves the read position to the end without returning anything.
     *
     * The log is a historical record that outlives any one IDE run, so a consumer that has
     * just started — or has just been switched on — must not treat what is already there as
     * having happened now.
     */
    fun skipToEnd() {
        offset = runCatching { if (file.exists()) file.fileSize() else 0L }.getOrDefault(0L)
    }

    /**
     * Resets the log, without destroying what has not been read.
     *
     * Two readers race the truncation: a hook appending between our read and the reset, and
     * another IDE process tailing the same file with its own offset. The first is answered by
     * re-checking the size through the same channel that truncates — a grown file keeps
     * everything for the next read. The second by only resetting a log that has been quiet
     * for a while, long enough for any other live tail to have drained it; [force] overrides
     * the quiet requirement when the log has grown far past its bound, trading another
     * process's unread events for a hard cap on disk growth.
     */
    private fun truncate(force: Boolean) {
        runCatching {
            if (!force) {
                val quietMillis =
                    System.currentTimeMillis() - file.getLastModifiedTime().toMillis()
                if (quietMillis < TRUNCATE_QUIET_MILLIS) return
            }
            FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
                if (channel.size() == offset) {
                    channel.truncate(0)
                    offset = 0
                }
            }
        }.onFailure { thisLogger().debug("Cannot truncate hook event log", it) }
    }

    private companion object {
        /** How long the log must sit unmodified before a non-forced reset may take it. */
        const val TRUNCATE_QUIET_MILLIS = 5_000L
    }
}
