package dev.andy.claudesessions.data

import com.intellij.openapi.diagnostic.thisLogger
import dev.andy.claudesessions.model.SessionItem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Removes a session from the list by moving its transcript out of `~/.claude/projects`.
 *
 * A move rather than a delete, deliberately. Removal happens without a confirmation
 * dialog, so it has to be recoverable — an accidental click must not destroy a transcript.
 * Claude stops listing the session (it is no longer under `projects/`), and the file is
 * still on disk under `~/.claude/claudesessions/archive/` if it turns out to be wanted.
 */
internal object SessionArchiver {

    val archiveDir: Path get() = ClaudePaths.pluginDir.resolve("archive")

    /** What was moved, so the caller can offer to put it back. */
    data class Archived(val sessionId: String, val from: Path, val to: Path, val sidecar: Pair<Path, Path>?)

    /**
     * A live session is still being appended to; moving its transcript would leave the
     * running process writing to a file nothing can find.
     */
    fun canArchive(item: SessionItem): Boolean = !item.isLive && item.summary.transcript.exists()

    fun archive(item: SessionItem): Archived? {
        val source = item.summary.transcript
        if (!source.exists()) return null

        return runCatching {
            Files.createDirectories(archiveDir)
            val target = uniqueTarget(archiveDir.resolve(source.name))
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)

            // Sidecars: subagent transcripts and offloaded tool output live in a directory
            // named after the session. Move it too, or the list is clean but the disk is not.
            val sidecarSource = source.resolveSibling(item.sessionId)
            val sidecar = if (sidecarSource.isDirectory()) {
                val sidecarTarget = uniqueTarget(archiveDir.resolve(item.sessionId))
                Files.move(sidecarSource, sidecarTarget, StandardCopyOption.ATOMIC_MOVE)
                sidecarSource to sidecarTarget
            } else {
                null
            }

            Archived(item.sessionId, source, target, sidecar)
        }.onFailure { thisLogger().warn("Cannot archive ${item.sessionId}", it) }.getOrNull()
    }

    /** Puts an archived session back where it came from. */
    fun restore(archived: Archived): Boolean = runCatching {
        Files.createDirectories(archived.from.parent)
        Files.move(archived.to, archived.from, StandardCopyOption.ATOMIC_MOVE)
        archived.sidecar?.let { (from, to) ->
            Files.move(to, from, StandardCopyOption.ATOMIC_MOVE)
        }
        true
    }.onFailure { thisLogger().warn("Cannot restore ${archived.sessionId}", it) }.getOrDefault(false)

    /** Archiving the same session twice must not clobber the first copy. */
    private fun uniqueTarget(preferred: Path): Path {
        if (!preferred.exists()) return preferred
        var index = 2
        while (true) {
            val candidate = preferred.resolveSibling("${preferred.name}.$index")
            if (!candidate.exists()) return candidate
            index++
        }
    }
}
