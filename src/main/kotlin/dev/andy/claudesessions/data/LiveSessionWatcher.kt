package dev.andy.claudesessions.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import dev.andy.claudesessions.model.LiveStatus
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Reads `~/.claude/sessions/<pid>.json`, the CLI's live-status sidecar.
 *
 * Roughly four files totalling ~16 KB, so reading the whole directory is cheap enough
 * to poll. Status is written edge-triggered with no heartbeat, so a stale `updatedAt`
 * says nothing about liveness — the pid is the only authority.
 */
internal class LiveSessionWatcher {

    /**
     * A pid file survives an unclean exit and is only reaped by the next `claude` launch,
     * so file existence alone means nothing. We additionally require that the pid is alive,
     * that it did not start long after the session did (pid reuse), and that it looks like
     * a claude process.
     */
    private val pidReuseTolerance: Duration = Duration.ofMinutes(2)

    fun poll(): Map<String, LiveStatus> {
        val dir = ClaudePaths.sessions
        if (!dir.isDirectory()) return emptyMap()

        val files = runCatching {
            dir.listDirectoryEntries().filter { it.extension == "json" }
        }.getOrElse { emptyList() }

        val result = HashMap<String, LiveStatus>()
        for (file in files) {
            val entry = runCatching { read(file) }
                .onFailure { thisLogger().debug("Unreadable session file $file", it) }
                .getOrNull() ?: continue
            result[entry.sessionId] = entry
        }
        return result
    }

    private fun read(file: Path): LiveStatus? {
        val text = runCatching {
            Files.readString(file, StandardCharsets.UTF_8)
        }.getOrNull() ?: return null

        val obj = runCatching { JsonParser.parseString(text) as? JsonObject }
            .getOrNull() ?: return null

        val pid = obj.long("pid") ?: return null
        val sessionId = obj.string("sessionId") ?: return null
        val startedAt = obj.long("startedAt")

        if (!isAlive(pid, startedAt)) return null

        return LiveStatus(
            pid = pid,
            sessionId = sessionId,
            cwd = obj.string("cwd"),
            // Absent for claude-desktop and SDK sessions: only the TUI writes status.
            status = obj.string("status"),
            waitingFor = obj.string("waitingFor"),
            entrypoint = obj.string("entrypoint"),
            kind = obj.string("kind"),
            name = obj.string("name"),
            startedAtMillis = startedAt,
        )
    }

    private fun isAlive(pid: Long, startedAtMillis: Long?): Boolean {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return false
        if (!handle.isAlive) return false

        val info = handle.info()

        // Guard against pid reuse: a recycled pid would have started well after the
        // session recorded its own start time.
        if (startedAtMillis != null) {
            val processStart = info.startInstant().orElse(null)
            if (processStart != null) {
                val sessionStart = Instant.ofEpochMilli(startedAtMillis)
                if (processStart.isAfter(sessionStart.plus(pidReuseTolerance))) return false
            }
        }

        // Cheap sanity check; covers the CLI, the desktop app and the agent SDK binaries.
        val command = info.command().orElse(null)
        if (command != null && !command.contains("claude", ignoreCase = true)) return false

        return true
    }

    private fun JsonObject.string(name: String): String? {
        val element = get(name) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive) return null
        return runCatching { element.asString }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.long(name: String): Long? {
        val element = get(name) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive) return null
        return runCatching { element.asLong }.getOrNull()
    }
}
