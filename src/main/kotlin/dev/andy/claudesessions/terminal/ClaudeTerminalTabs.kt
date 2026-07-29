package dev.andy.claudesessions.terminal

import com.intellij.openapi.components.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks the open editor tab per session id.
 *
 * Holding on to the exact [ClaudeTerminalFile] instance is what makes "click the session
 * again" focus the existing tab rather than open a duplicate.
 */
@Service(Service.Level.PROJECT)
internal class ClaudeTerminalTabs {

    /**
     * A tab opened with `+`, waiting to be matched to the session Claude actually created.
     *
     * The real session id does not exist until the CLI writes it, so the tab starts under
     * a synthetic key and is rebound once a matching live session shows up.
     */
    class PendingLink(
        val file: ClaudeTerminalFile,
        val workingDirectory: String?,
        val launchedAtMillis: Long,
    )

    private val bySessionId = ConcurrentHashMap<String, ClaudeTerminalFile>()
    private val pending = CopyOnWriteArrayList<PendingLink>()

    fun find(sessionId: String): ClaudeTerminalFile? = bySessionId[sessionId]

    fun remember(file: ClaudeTerminalFile) {
        bySessionId[file.sessionId] = file
    }

    fun forget(sessionId: String) {
        bySessionId.remove(sessionId)
        pending.removeIf { it.file.sessionId == sessionId }
    }

    fun openSessionIds(): Set<String> = bySessionId.keys.toSet()

    fun awaitLink(file: ClaudeTerminalFile, workingDirectory: String?, nowMillis: Long) {
        pending += PendingLink(file, workingDirectory, nowMillis)
    }

    fun pendingLinks(): List<PendingLink> = pending.toList()

    /** Rebinds the tab from its synthetic key to the session id Claude assigned. */
    fun resolveLink(link: PendingLink, realSessionId: String) {
        pending.remove(link)
        bySessionId.remove(link.file.sessionId)
        link.file.bindSessionId(realSessionId)
        bySessionId[realSessionId] = link.file
    }

    /**
     * Stops waiting for links that never matched, so we do not retry forever.
     * Returns what was dropped so the caller can report it.
     */
    fun expirePendingLinks(nowMillis: Long): List<PendingLink> {
        val expired = pending.filter { nowMillis - it.launchedAtMillis > LINK_TIMEOUT_MILLIS }
        pending.removeAll(expired)
        return expired
    }

    companion object {
        private const val LINK_TIMEOUT_MILLIS = 3 * 60 * 1000L
    }
}
