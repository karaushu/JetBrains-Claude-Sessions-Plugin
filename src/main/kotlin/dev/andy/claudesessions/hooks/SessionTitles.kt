package dev.andy.claudesessions.hooks

/**
 * Remembers what Claude calls each session, as seen in the hook stream.
 *
 * `session_title` rides along on `SessionStart` and `UserPromptSubmit` but not on `Stop` or
 * `Notification` — precisely the two events worth announcing. Keeping it here means a
 * notification can name its session without opening the transcript.
 *
 * Bounded: a long-lived IDE would otherwise accumulate an entry per session ever seen.
 */
internal class SessionTitles(private val capacity: Int = 256) {

    private val titles = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > capacity
    }

    /** Records the title this event carries, if it carries one. */
    fun remember(event: HookEvent) {
        val title = event.sessionTitle ?: return
        synchronized(titles) { titles[event.sessionId] = title }
    }

    operator fun get(sessionId: String): String? = synchronized(titles) { titles[sessionId] }

    val size: Int get() = synchronized(titles) { titles.size }
}
