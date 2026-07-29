package dev.andy.claudesessions.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import dev.andy.claudesessions.ClaudeSessionsIcons
import dev.andy.claudesessions.model.SessionState
import javax.swing.Icon

/**
 * The single source of truth for a session's status icon.
 *
 * Both the tool window list and the editor tab read from here, so the two can never
 * disagree about what a session's state looks like.
 *
 * The scheme is one glyph — the Claude mark — in two colours, plus two loud states:
 * orange means the session is alive, neutral grey means it is only a transcript on disk,
 * a spinner means Claude is working, and the attention icon means it wants you.
 */
internal object SessionStateIcons {

    fun of(state: SessionState): Icon = when (state) {
        // Animates in the list because the list sets ANIMATION_IN_RENDERER_ALLOWED.
        SessionState.RUNNING -> AnimatedIcon.Default.INSTANCE
        SessionState.NEEDS_INPUT -> AllIcons.General.BalloonWarning
        // Alive: orange mark. `shell` and `idle` both land here, as does a live session
        // whose entrypoint never writes a status (Claude Desktop, agent SDK).
        SessionState.LIVE_IDLE, SessionState.RUNNING_UNKNOWN -> ClaudeSessionsIcons.SessionLive
        SessionState.HISTORICAL -> ClaudeSessionsIcons.Session
    }

    /**
     * Icon for an editor tab.
     *
     * A tab always belongs to a session we started, so the default is the live orange
     * mark — including before any status has been observed ([state] is null), which is
     * the case for a brand-new terminal and for anything opened with the `+` button.
     */
    fun forTab(state: SessionState?): Icon = when (state) {
        SessionState.RUNNING -> AnimatedIcon.Default.INSTANCE
        SessionState.NEEDS_INPUT -> AllIcons.General.BalloonWarning
        // The process is gone; the tab is about to close with it.
        SessionState.HISTORICAL -> ClaudeSessionsIcons.Session
        else -> ClaudeSessionsIcons.SessionLive
    }
}
