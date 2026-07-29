package dev.andy.claudesessions.hooks

import dev.andy.claudesessions.model.SessionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HookEventTest {

    private fun event(name: String, notificationType: String? = null) =
        HookEvent("s1", name, notificationType, "/repo")

    @Test
    fun `a submitted prompt means a turn is running`() {
        assertEquals(HookDerivedState.RUNNING, event("UserPromptSubmit").derivedState())
    }

    @Test
    fun `stop and session start mean idle at the prompt`() {
        assertEquals(HookDerivedState.IDLE, event("Stop").derivedState())
        assertEquals(HookDerivedState.IDLE, event("SessionStart").derivedState())
    }

    @Test
    fun `session end means the session is gone`() {
        assertEquals(HookDerivedState.ENDED, event("SessionEnd").derivedState())
    }

    @Test
    fun `blocking notifications mean Claude needs input`() {
        for (type in listOf(
            "permission_prompt",
            "agent_needs_input",
            "worker_permission_prompt",
            "elicitation_dialog",
            "elicitation_url_dialog",
        )) {
            assertEquals(
                HookDerivedState.NEEDS_INPUT,
                event("Notification", type).derivedState(),
                "notification_type $type",
            )
        }
    }

    @Test
    fun `your-turn notifications are idle, matching what the pid file reports`() {
        assertEquals(HookDerivedState.IDLE, event("Notification", "idle_prompt").derivedState())
        assertEquals(HookDerivedState.IDLE, event("Notification", "agent_completed").derivedState())
    }

    @Test
    fun `unknown notification types are ignored rather than guessed at`() {
        // Claude declares notification_type as an open string, not an enum.
        assertNull(event("Notification", "auth_success").derivedState())
        assertNull(event("Notification", "something_added_later").derivedState())
        assertNull(event("Notification", null).derivedState())
    }

    @Test
    fun `events we do not act on say nothing about state`() {
        assertNull(event("PreToolUse").derivedState())
        assertNull(event("PreCompact").derivedState())
    }

    @Test
    fun `derived states map onto the icons the UI already knows`() {
        assertEquals(SessionState.RUNNING, HookDerivedState.RUNNING.toSessionState())
        assertEquals(SessionState.NEEDS_INPUT, HookDerivedState.NEEDS_INPUT.toSessionState())
        assertEquals(SessionState.LIVE_IDLE, HookDerivedState.IDLE.toSessionState())
        assertEquals(SessionState.HISTORICAL, HookDerivedState.ENDED.toSessionState())
    }
}
