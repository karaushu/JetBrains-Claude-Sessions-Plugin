package dev.andy.claudesessions.hooks

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import dev.andy.claudesessions.data.ClaudePaths
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.fileSize

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

        val text = runCatching { readFrom(offset, size) }
            .onFailure { thisLogger().debug("Cannot read hook event log", it) }
            .getOrNull() ?: return emptyList()

        offset = size

        val events = text.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull(::parse)
            .toList()

        if (size > maxBytes) truncate()
        return events
    }

    private fun readFrom(from: Long, to: Long): String {
        Files.newByteChannel(file, StandardOpenOption.READ).use { channel ->
            channel.position(from)
            val buffer = java.nio.ByteBuffer.allocate((to - from).toInt().coerceAtLeast(0))
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
            return String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8)
        }
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

    private fun truncate() {
        runCatching {
            Files.write(file, ByteArray(0), StandardOpenOption.TRUNCATE_EXISTING)
            offset = 0
        }.onFailure { thisLogger().debug("Cannot truncate hook event log", it) }
    }

    private fun JsonObject.string(name: String): String? {
        val element = get(name) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive) return null
        return runCatching { element.asString }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.boolean(name: String): Boolean? {
        val element = get(name) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive) return null
        return runCatching { element.asBoolean }.getOrNull()
    }
}
