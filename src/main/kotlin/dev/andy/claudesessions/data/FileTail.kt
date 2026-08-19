package dev.andy.claudesessions.data

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The byte-range read both append-only tails use — the hook event log and a round's replies
 * file. One body, so a fix to the read loop cannot be applied to one and missed in the other.
 */
internal object FileTail {

    /** Bytes `[from, to)` of [file]; short when the file shrank between stat and read. */
    fun readBytes(file: Path, from: Long, to: Long): ByteArray {
        Files.newByteChannel(file, StandardOpenOption.READ).use { channel ->
            channel.position(from)
            val buffer = ByteBuffer.allocate((to - from).toInt().coerceAtLeast(0))
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
            return buffer.array().copyOf(buffer.position())
        }
    }
}
