package dev.andy.claudesessions.review

import com.intellij.openapi.diagnostic.thisLogger
import dev.andy.claudesessions.data.FileTail
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize

/**
 * Tails the file one round's replies are appended to.
 *
 * The same byte-offset approach as [dev.andy.claudesessions.hooks.HookEventLog], for the same
 * reason: a `stat` plus a read of what is new is cheap enough to do several times a second, and
 * a torn line simply fails to parse. Four differences, each because the writer here is a
 * language model rather than a shell command:
 *
 * - Nothing is ever truncated. The hook log grows without bound and truncates itself; this file
 *   belongs to one round, and cutting it would race the agent's own appends and destroy the only
 *   record of what it said.
 * - There is no `skipToEnd`. A backlog is exactly what we want — replies written while the IDE
 *   was closed must still land.
 * - The offset is readable and settable, because it is persisted per round so a restart resumes
 *   mid-file rather than replaying.
 * - Only complete lines are consumed: a trailing partial line stays in the file and the offset
 *   stops before it, so the persisted position never points into a half-written reply — and a
 *   restart cannot lose it. A line that opens an object without closing it is joined with the
 *   lines after it: pretty-printing a JSONL file is the single most likely way a model breaks
 *   the protocol, and recovering costs a few lines here.
 */
internal class ReviewReplyLog(private val file: Path, startOffset: Long = 0) {

    var offset: Long = startOffset
        private set

    /** True once the file grew past what is worth reading; the round is reported as broken. */
    var overflowed: Boolean = false
        private set

    /** Replies appended since the last call. Empty whenever nothing changed. */
    fun readNew(): List<ReviewReply> {
        if (overflowed || !file.exists()) return emptyList()

        val size = runCatching { file.fileSize() }.getOrNull() ?: return emptyList()
        if (size > MAX_BYTES) {
            overflowed = true
            thisLogger().warn("Replies file $file grew past ${MAX_BYTES / 1024} KB; giving up on it")
            return emptyList()
        }

        // Smaller than where we were: the agent rewrote the file instead of appending. Reading
        // it again from the start is safe, because applying a reply twice is a no-op.
        if (size < offset) offset = 0
        if (size == offset) return emptyList()

        val bytes = runCatching { FileTail.readBytes(file, offset, size) }
            .onFailure { thisLogger().debug("Cannot read replies file $file", it) }
            .getOrNull() ?: return emptyList()

        // Consume up to the last newline only. Splitting on bytes rather than decoded text
        // also keeps a multi-byte character on a read boundary intact for the next pass.
        val consumed = bytes.lastIndexOf(NEWLINE) + 1
        if (consumed == 0) return emptyList()

        offset += consumed
        return parse(String(bytes, 0, consumed, StandardCharsets.UTF_8))
    }

    /** Restores a persisted position, so a restart mid-round does not replay the whole file. */
    fun resumeAt(savedOffset: Long) {
        offset = savedOffset.coerceAtLeast(0)
    }

    private fun parse(text: String): List<ReviewReply> {
        val complete = text.split('\n')

        val replies = mutableListOf<ReviewReply>()
        var index = 0
        while (index < complete.size) {
            val line = complete[index].trim()
            index++
            if (line.isEmpty()) continue
            if (line.length > MAX_LINE_CHARS) continue

            val direct = ReviewReply.parse(line)
            if (direct != null) {
                replies += direct
                continue
            }
            // Possibly a pretty-printed object: join the following lines until it parses.
            if (!line.startsWith('{')) continue
            val joined = StringBuilder(line)
            var joinedLines = 0
            while (index < complete.size && joinedLines < MAX_JOINED_LINES) {
                joined.append(' ').append(complete[index].trim())
                index++
                joinedLines++
                val recovered = ReviewReply.parse(joined.toString())
                if (recovered != null) {
                    replies += recovered
                    break
                }
            }
        }
        return replies
    }

    private fun ByteArray.lastIndexOf(byte: Byte): Int {
        for (index in lastIndex downTo 0) {
            if (this[index] == byte) return index
        }
        return -1
    }

    private companion object {
        const val MAX_BYTES = 256 * 1024L

        const val MAX_LINE_CHARS = 16 * 1024

        const val MAX_JOINED_LINES = 12

        const val NEWLINE = '\n'.code.toByte()
    }
}
