package dev.andy.claudesessions.review

import com.intellij.openapi.components.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Half-written notes, kept alive across everything that recreates a diff viewer.
 *
 * The agent rewrites the file while the user is typing about it, and each write makes the viewer
 * run its diff again and rebuild what is drawn. Losing a half-typed note to that — or to
 * clicking the next file and back — would be maddening in exactly the situation the feature
 * exists for.
 *
 * Strings only, never components. A service holding a live `JComponent` would keep a disposed
 * editor's whole hierarchy alive, which is the one leak this design must not have.
 */
@Service(Service.Level.PROJECT)
internal class ReviewDrafts {

    private val byLine = ConcurrentHashMap<String, String>()

    fun put(path: String, line: Int, text: String) {
        if (text.isBlank()) remove(path, line) else byLine[key(path, line)] = text
    }

    fun get(path: String, line: Int): String? = byLine[key(path, line)]

    fun remove(path: String, line: Int) {
        byLine.remove(key(path, line))
    }

    /** The lines in one file that have a draft waiting, so the viewer can redraw them. */
    fun linesIn(path: String): List<Int> = byLine.keys
        .filter { it.startsWith("$path:") }
        .mapNotNull { it.substringAfterLast(':').toIntOrNull() }
        .sorted()

    /**
     * A half-written reply is keyed by thread rather than by line, because the thread it belongs
     * to moves when the agent edits the file above it.
     */
    fun putReply(threadId: String, text: String) {
        if (text.isBlank()) byThread.remove(threadId) else byThread[threadId] = text
    }

    fun reply(threadId: String): String? = byThread[threadId]

    fun removeReply(threadId: String) {
        byThread.remove(threadId)
    }

    private val byThread = ConcurrentHashMap<String, String>()

    private fun key(path: String, line: Int) = "$path:$line"
}
