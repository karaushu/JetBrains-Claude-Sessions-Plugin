package dev.andy.claudesessions.review

import dev.andy.claudesessions.model.SessionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewGateTest {

    private fun gate(
        count: Int = 3,
        sessionId: String? = "sess-1",
        title: String? = "Fix auth flow",
        tabOpen: Boolean = true,
        state: SessionState? = SessionState.LIVE_IDLE,
        liveStatus: String? = "idle",
    ) = ReviewGate.evaluate(count, sessionId, title, tabOpen, state, liveStatus)

    @Test
    fun `an idle session takes the round without asking`() {
        val result = gate()

        assertTrue(result.isReady)
        assertNull(result.problem())
        assertEquals(SendGate.Ready("sess-1", "Fix auth flow"), result)
    }

    @Test
    fun `nothing to send outranks every other reason`() {
        assertEquals(SendGate.NothingToSend, gate(count = 0, sessionId = null, tabOpen = false))
    }

    @Test
    fun `with no session open there is no target`() {
        assertEquals(SendGate.NoTarget, gate(sessionId = null))
    }

    @Test
    fun `a working session asks first because typed text is queued mid-turn`() {
        val result = gate(state = SessionState.RUNNING, liveStatus = "busy")

        assertFalse(result.isReady)
        assertTrue(result.needsConfirmation)
        assertEquals("Claude is working in Fix auth flow", result.problem())
    }

    @Test
    fun `a session waiting on an answer is refused outright`() {
        val result = gate(state = SessionState.NEEDS_INPUT, liveStatus = "waiting")

        assertFalse(result.isReady)
        assertFalse(
            result.needsConfirmation,
            "there must be no send-anyway: the line would answer a permission prompt",
        )
        assertEquals("Claude is waiting for an answer in Fix auth flow", result.problem())
    }

    @Test
    fun `a shell prompt is refused, since the line would become command not found`() {
        val result = gate(state = SessionState.LIVE_IDLE, liveStatus = "shell")

        assertFalse(result.isReady)
        assertFalse(result.needsConfirmation)
        assertEquals(SendGate.ShellOnly("Fix auth flow"), result)
    }

    @Test
    fun `an unknown state is sendable but says so`() {
        val result = gate(state = SessionState.RUNNING_UNKNOWN, liveStatus = null)

        assertFalse(result.isReady)
        assertTrue(result.needsConfirmation)
        assertEquals("Cannot tell what Fix auth flow is doing", result.problem())
    }

    @Test
    fun `a closed tab is reported rather than reopened behind the user's back`() {
        val result = gate(tabOpen = false)

        assertEquals(SendGate.TabClosed("sess-1", "Fix auth flow"), result)
        assertEquals("The tab for Fix auth flow is closed", result.problem())
    }

    @Test
    fun `a session with no title still reads as a sentence`() {
        assertEquals("Claude is working in this session", gate(title = null, state = SessionState.RUNNING).problem())
    }

    @Test
    fun `a model-generated title cannot smuggle newlines into the message`() {
        val result = gate(title = "line one\nline two", state = SessionState.RUNNING)

        assertFalse(result.problem()!!.contains('\n'))
    }
}
