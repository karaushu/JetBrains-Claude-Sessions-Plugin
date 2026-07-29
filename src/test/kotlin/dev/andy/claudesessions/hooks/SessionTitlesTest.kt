package dev.andy.claudesessions.hooks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SessionTitlesTest {

    private fun event(sessionId: String, name: String, title: String? = null) =
        HookEvent(sessionId, name, null, "/repo", sessionTitle = title)

    @Test
    fun `remembers the title a session start carried`() {
        val titles = SessionTitles()
        titles.remember(event("a", "SessionStart", "JetBrains IDE plugin for WebStorm"))
        assertEquals("JetBrains IDE plugin for WebStorm", titles["a"])
    }

    @Test
    fun `an event with no title leaves the remembered one alone`() {
        // Stop and Notification never carry session_title, and they are the two events that
        // need it. Clearing on absence would make every notification anonymous.
        val titles = SessionTitles()
        titles.remember(event("a", "UserPromptSubmit", "Toast system"))
        titles.remember(event("a", "Stop"))
        titles.remember(event("a", "Notification"))
        assertEquals("Toast system", titles["a"])
    }

    @Test
    fun `a renamed session takes the newer title`() {
        val titles = SessionTitles()
        titles.remember(event("a", "SessionStart", "Untitled"))
        titles.remember(event("a", "UserPromptSubmit", "Notifications for finished turns"))
        assertEquals("Notifications for finished turns", titles["a"])
    }

    @Test
    fun `an unknown session has no title`() {
        assertNull(SessionTitles()["nope"])
    }

    @Test
    fun `the map is bounded so a long-lived IDE does not accumulate every session`() {
        val titles = SessionTitles(capacity = 3)
        repeat(10) { i -> titles.remember(event("s$i", "SessionStart", "title $i")) }

        assertEquals(3, titles.size)
        assertEquals("title 9", titles["s9"])
        assertNull(titles["s0"])
    }

    @Test
    fun `a session read recently survives eviction`() {
        val titles = SessionTitles(capacity = 2)
        titles.remember(event("keep", "SessionStart", "kept"))
        titles.remember(event("b", "SessionStart", "b"))
        // Access order: touching "keep" makes "b" the eldest.
        assertEquals("kept", titles["keep"])
        titles.remember(event("c", "SessionStart", "c"))

        assertEquals("kept", titles["keep"])
        assertNull(titles["b"])
    }
}
