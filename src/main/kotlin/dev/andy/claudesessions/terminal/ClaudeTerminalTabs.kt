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

    /**
     * Most recently focused first, so a feature that has to pick a session on the user's
     * behalf — sending diff review notes, for one — can default to the one they were last
     * looking at. Fed by [ClaudeTabFocusTracker]; ids of closed tabs are dropped.
     */
    private val focusOrder = CopyOnWriteArrayList<String>()

    fun find(sessionId: String): ClaudeTerminalFile? = bySessionId[sessionId]

    fun remember(file: ClaudeTerminalFile) {
        bySessionId[file.sessionId] = file
    }

    fun forget(sessionId: String) {
        bySessionId.remove(sessionId)
        pending.removeIf { it.file.sessionId == sessionId }
        focusOrder.remove(sessionId)
    }

    /**
     * Forgets [file] only while it is still the registered tab for its id. Editor disposal
     * is deferred through the event queue, and the session can be reopened as a *new* file
     * meanwhile — forgetting by id alone would delete that fresh tab's registration.
     */
    fun forget(file: ClaudeTerminalFile) {
        bySessionId.remove(file.sessionId, file)
        pending.removeIf { it.file === file }
        if (!bySessionId.containsKey(file.sessionId)) focusOrder.remove(file.sessionId)
    }

    fun noteFocused(sessionId: String) {
        focusOrder.remove(sessionId)
        focusOrder.add(0, sessionId)
        while (focusOrder.size > MAX_FOCUS_HISTORY) focusOrder.removeAt(focusOrder.size - 1)
    }

    /** Only sessions whose tab is still open; a remembered id outlives nothing. */
    fun focusedSessionIds(): List<String> = focusOrder.filter { bySessionId.containsKey(it) }

    fun openSessionIds(): Set<String> = bySessionId.keys.toSet()

    fun awaitLink(file: ClaudeTerminalFile, workingDirectory: String?, nowMillis: Long) {
        pending += PendingLink(file, workingDirectory, nowMillis)
    }

    fun pendingLinks(): List<PendingLink> = pending.toList()

    /**
     * True while a `+` tab in [workingDirectory] is still waiting for its session id.
     *
     * Adoption needs the session to appear in the list, which only happens while the tool
     * window is refreshing. Until then the tab owns a session whose id nobody knows, so a
     * caller asking "is this session mine?" gets a yes on the strength of the directory
     * alone. That is the same evidence [SessionAdoption] uses, minus the transcript.
     */
    fun hasPendingLinkIn(workingDirectory: String?): Boolean =
        workingDirectory != null && pending.any { it.workingDirectory == workingDirectory }

    /** Rebinds the tab from its synthetic key to the session id Claude assigned. */
    fun resolveLink(link: PendingLink, realSessionId: String) {
        val syntheticKey = link.file.sessionId
        pending.remove(link)
        bySessionId.remove(syntheticKey)
        link.file.bindSessionId(realSessionId)
        bySessionId[realSessionId] = link.file
        // A '+' tab is focused before Claude has given it an id, so its place in the focus
        // order was recorded under the synthetic key and has to move with it.
        val focusedAt = focusOrder.indexOf(syntheticKey)
        if (focusedAt >= 0) {
            focusOrder.removeAt(focusedAt)
            focusOrder.add(focusedAt, realSessionId)
        }
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

        private const val MAX_FOCUS_HISTORY = 16
    }
}
