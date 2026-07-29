package dev.andy.claudesessions.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import dev.andy.claudesessions.ClaudeSessionsIcons
import dev.andy.claudesessions.model.SessionState
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SessionStateIconsTest {

    @Test
    fun `a live session is the orange Claude mark, not a check mark`() {
        assertSame(ClaudeSessionsIcons.SessionLive, SessionStateIcons.of(SessionState.LIVE_IDLE))
        assertSame(ClaudeSessionsIcons.SessionLive, SessionStateIcons.of(SessionState.RUNNING_UNKNOWN))
        assertNotSame(AllIcons.General.InspectionsOK, SessionStateIcons.of(SessionState.LIVE_IDLE))
    }

    @Test
    fun `a stopped session is the neutral Claude mark`() {
        assertSame(ClaudeSessionsIcons.Session, SessionStateIcons.of(SessionState.HISTORICAL))
    }

    @Test
    fun `live and stopped are visually distinct`() {
        assertNotSame(
            SessionStateIcons.of(SessionState.HISTORICAL),
            SessionStateIcons.of(SessionState.LIVE_IDLE),
        )
    }

    @Test
    fun `a tab with no status yet shows the live mark, never an info badge`() {
        // Regression: a brand-new tab used to render RUNNING_UNKNOWN's blue info badge.
        val fresh = SessionStateIcons.forTab(null)
        assertSame(ClaudeSessionsIcons.SessionLive, fresh)
        assertNotSame(AllIcons.General.BalloonInformation, fresh)
        assertSame(fresh, SessionStateIcons.forTab(SessionState.LIVE_IDLE))
    }

    @Test
    fun `a tab shows a spinner while working and the attention icon when input is needed`() {
        assertSame(AnimatedIcon.Default.INSTANCE, SessionStateIcons.forTab(SessionState.RUNNING))
        assertSame(AllIcons.General.BalloonWarning, SessionStateIcons.forTab(SessionState.NEEDS_INPUT))
    }

    @Test
    fun `tab and list agree on every state they share`() {
        // The whole point of sharing this object: these must never drift apart.
        for (state in listOf(
            SessionState.RUNNING,
            SessionState.NEEDS_INPUT,
            SessionState.LIVE_IDLE,
            SessionState.RUNNING_UNKNOWN,
            SessionState.HISTORICAL,
        )) {
            assertSame(SessionStateIcons.of(state), SessionStateIcons.forTab(state), "state $state")
        }
    }
}
