package dev.andy.claudesessions.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ClaudeTerminalTabsTest {

    @Test
    fun `find returns null for an unknown session`() {
        assertNull(ClaudeTerminalTabs().find("nope"))
    }

    @Test
    fun `forget is safe for an unknown session`() {
        val tabs = ClaudeTerminalTabs()
        tabs.forget("nope")
        assertEquals(emptySet<String>(), tabs.openSessionIds())
    }
}
