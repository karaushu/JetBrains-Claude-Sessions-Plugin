package dev.andy.claudesessions.terminal

import com.intellij.terminal.frontend.view.TerminalView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * Exercises the adoption wiring end to end, minus the IDE.
 *
 * This is the part that could only be observed by clicking `+` and watching a tab title,
 * which is exactly why it went wrong repeatedly without leaving evidence. A proxy stands in
 * for the terminal view: none of its methods are called on this path, only the identity of
 * the file that holds it.
 */
class ClaudeTerminalTabsWiringTest {

    private fun fakeView(): TerminalView =
        Proxy.newProxyInstance(
            TerminalView::class.java.classLoader,
            arrayOf(TerminalView::class.java),
        ) { _, _, _ -> null } as TerminalView

    private fun newSessionFile(key: String = "new:abc") =
        ClaudeTerminalFile(key, fakeView(), "New session")

    @Test
    fun `a plus tab is findable under its synthetic key before adoption`() {
        val tabs = ClaudeTerminalTabs()
        val file = newSessionFile()
        tabs.remember(file)
        tabs.awaitLink(file, "/repo", 1_000L)

        assertNotNull(tabs.find("new:abc"))
        assertEquals(setOf("new:abc"), tabs.openSessionIds())
        assertEquals(1, tabs.pendingLinks().size)
    }

    @Test
    fun `resolving a link rebinds the tab to the real session id`() {
        val tabs = ClaudeTerminalTabs()
        val file = newSessionFile()
        tabs.remember(file)
        tabs.awaitLink(file, "/repo", 1_000L)

        tabs.resolveLink(tabs.pendingLinks().single(), "real-session-id")

        // This is what makes clicking the row focus the tab instead of warning that the
        // session is running elsewhere.
        assertNotNull(tabs.find("real-session-id"))
        assertNull(tabs.find("new:abc"), "the synthetic key must not linger")
        assertEquals(setOf("real-session-id"), tabs.openSessionIds())
        assertEquals("real-session-id", file.sessionId)
        assertTrue(tabs.pendingLinks().isEmpty())
    }

    @Test
    fun `the whole path from launch to focus-by-real-id`() {
        val tabs = ClaudeTerminalTabs()
        val file = newSessionFile()

        // 1. openNewSession: remember under a synthetic key, then wait for a match.
        tabs.remember(file)
        tabs.awaitLink(file, "/repo", 1_000L)
        assertFalse("real" in tabs.openSessionIds())

        // 2. A refresh finds a candidate and adopts it.
        val link = tabs.pendingLinks().single()
        tabs.resolveLink(link, "real")

        // 3. isOpen(realId) is what ClaudeSessionsPanel consults on click.
        assertNotNull(tabs.find("real"))
    }

    @Test
    fun `closing a pending tab stops us waiting for its session`() {
        val tabs = ClaudeTerminalTabs()
        val file = newSessionFile()
        tabs.remember(file)
        tabs.awaitLink(file, "/repo", 1_000L)

        tabs.forget(file.sessionId)

        assertTrue(tabs.pendingLinks().isEmpty(), "a closed tab must not keep a pending link")
        assertTrue(tabs.openSessionIds().isEmpty())
    }

    @Test
    fun `expiry reports what it dropped, and only what is actually stale`() {
        val tabs = ClaudeTerminalTabs()
        val old = newSessionFile("new:old")
        val fresh = newSessionFile("new:fresh")
        tabs.remember(old)
        tabs.remember(fresh)
        tabs.awaitLink(old, "/repo", 0L)
        tabs.awaitLink(fresh, "/repo", 10 * 60 * 1000L)

        val expired = tabs.expirePendingLinks(nowMillis = 10 * 60 * 1000L)

        assertEquals(1, expired.size)
        assertEquals("new:old", expired.single().file.sessionId)
        assertEquals(1, tabs.pendingLinks().size)
    }

    @Test
    fun `the tab file can be renamed, which is how a title reaches the tab`() {
        // Regression: the file was created read-only, and LightVirtualFileBase.rename calls
        // assertWritable() first — so every retitle threw and tabs kept showing
        // "New session" long after Claude had named the session.
        val file = newSessionFile()
        file.rename(this, "Claude · Write ok function")
        assertEquals("Claude · Write ok function", file.name)
    }

    @Test
    fun `renaming survives a state change, so icon and title do not fight`() {
        val file = newSessionFile()
        file.rename(this, "Claude · named")
        file.updateState(dev.andy.claudesessions.model.SessionState.RUNNING)
        assertEquals("Claude · named", file.name)
    }

    @Test
    fun `two plus tabs do not adopt the same session`() {
        val tabs = ClaudeTerminalTabs()
        val first = newSessionFile("new:1")
        val second = newSessionFile("new:2")
        tabs.remember(first)
        tabs.remember(second)
        tabs.awaitLink(first, "/repo", 1_000L)
        tabs.awaitLink(second, "/repo", 1_000L)

        tabs.resolveLink(tabs.pendingLinks().first { it.file.sessionId == "new:1" }, "session-a")

        // openSessionIds is exactly the `claimed` set the launcher feeds to SessionAdoption,
        // so the second tab can never be handed the session the first one took.
        assertTrue("session-a" in tabs.openSessionIds())
        assertEquals(1, tabs.pendingLinks().size)
        assertEquals("new:2", tabs.pendingLinks().single().file.sessionId)
        assertEquals("session-a", first.sessionId)
        assertEquals("new:2", second.sessionId, "the unadopted tab keeps its synthetic key")
    }

    @Test
    fun `the focus order puts the session looked at last in front`() {
        val tabs = ClaudeTerminalTabs()
        tabs.remember(ClaudeTerminalFile("a", fakeView(), null))
        tabs.remember(ClaudeTerminalFile("b", fakeView(), null))

        tabs.noteFocused("a")
        tabs.noteFocused("b")
        tabs.noteFocused("a")

        assertEquals(listOf("a", "b"), tabs.focusedSessionIds(), "and never duplicates an id")
    }

    @Test
    fun `a session whose tab closed drops out of the focus order`() {
        val tabs = ClaudeTerminalTabs()
        tabs.remember(ClaudeTerminalFile("a", fakeView(), null))
        tabs.noteFocused("a")

        tabs.forget("a")

        assertTrue(tabs.focusedSessionIds().isEmpty())
    }

    @Test
    fun `a session never focused is not offered as one that was`() {
        val tabs = ClaudeTerminalTabs()
        tabs.remember(ClaudeTerminalFile("a", fakeView(), null))

        assertTrue(tabs.focusedSessionIds().isEmpty())
    }

    @Test
    fun `adoption carries the plus tab's place in the focus order to its real id`() {
        val tabs = ClaudeTerminalTabs()
        val other = ClaudeTerminalFile("older", fakeView(), null)
        val file = newSessionFile()
        tabs.remember(other)
        tabs.remember(file)
        tabs.awaitLink(file, "/repo", 1_000L)
        tabs.noteFocused("older")
        // A '+' tab is focused before Claude has given it an id.
        tabs.noteFocused("new:abc")

        tabs.resolveLink(tabs.pendingLinks().single(), "real-session-id")

        assertEquals(listOf("real-session-id", "older"), tabs.focusedSessionIds())
    }
}
