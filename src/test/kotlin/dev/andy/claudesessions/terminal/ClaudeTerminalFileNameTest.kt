package dev.andy.claudesessions.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClaudeTerminalFileNameTest {

    private val sessionId = "3a36bb2a-1111-2222-3333-444455556666"

    @Test
    fun `uses the session title`() {
        assertEquals(
            "Claude · Design dropdown menu component API",
            ClaudeTerminalFile.displayName(sessionId, "Design dropdown menu component API"),
        )
    }

    @Test
    fun `falls back to a short session id when there is no title`() {
        assertEquals("Claude · 3a36bb2a", ClaudeTerminalFile.displayName(sessionId, null))
        assertEquals("Claude · 3a36bb2a", ClaudeTerminalFile.displayName(sessionId, "   "))
    }

    @Test
    fun `collapses a multi-line title onto the tab label`() {
        val name = ClaudeTerminalFile.displayName(sessionId, "fix the\nflaky\ttest")
        assertEquals("Claude · fix the flaky test", name)
    }

    @Test
    fun `truncates a long title so the tab stays readable`() {
        val name = ClaudeTerminalFile.displayName(sessionId, "x".repeat(200))
        assertTrue(name.length < 60, "tab label too long: ${name.length}")
        assertTrue(name.endsWith("…"))
    }

    @Test
    fun `does not let an html looking title become markup`() {
        // Titles are model-generated and therefore untrusted.
        val name = ClaudeTerminalFile.displayName(sessionId, "<html><b>x</b>")
        assertEquals("Claude · <html><b>x</b>", name)
        assertFalse(name.startsWith("<html"))
    }
}
