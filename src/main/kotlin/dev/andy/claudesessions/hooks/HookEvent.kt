package dev.andy.claudesessions.hooks

import dev.andy.claudesessions.model.SessionState

/**
 * State a session is in according to Claude's hooks.
 *
 * Hooks are the only signal that works for every entrypoint. `~/.claude/sessions/<pid>.json`
 * carries a `status` only for the interactive TUI, so Claude Desktop and agent-SDK sessions
 * are invisible to polling.
 */
internal enum class HookDerivedState {
    /** A turn is in flight. */
    RUNNING,

    /** Blocked on the user: a permission prompt, an elicitation, an explicit ask. */
    NEEDS_INPUT,

    /** Alive and at the prompt. */
    IDLE,

    /** The session ended. */
    ENDED,
    ;

    fun toSessionState(): SessionState = when (this) {
        RUNNING -> SessionState.RUNNING
        NEEDS_INPUT -> SessionState.NEEDS_INPUT
        IDLE -> SessionState.LIVE_IDLE
        ENDED -> SessionState.HISTORICAL
    }
}

/**
 * One line of the hook event log.
 *
 * Field names are Claude's, which are snake_case: `session_id`, `hook_event_name`,
 * `notification_type`.
 */
internal data class HookEvent(
    val sessionId: String,
    val eventName: String,
    val notificationType: String?,
    val cwd: String?,
    /**
     * Claude's own wording for a `Notification`, e.g. "Claude needs your permission".
     * Preferred over anything we could compose, because it is what Claude itself would say.
     */
    val message: String? = null,
    /** Carried by `SessionStart` and `UserPromptSubmit`, once Claude has named the session. */
    val sessionTitle: String? = null,
    /** Claude's closing message for the turn. Carried by `Stop`. */
    val lastAssistantMessage: String? = null,
    /** Where the session's transcript lives; a fallback source for its title. */
    val transcriptPath: String? = null,
    /**
     * True when `Stop` is firing again because a stop hook asked Claude to keep going. The
     * turn has not actually ended, so it is not something to announce.
     */
    val stopHookActive: Boolean = false,
) {
    /**
     * The state this event implies, or null if it says nothing about state.
     *
     * `notification_type` is declared as an open string in Claude's schema, not an enum, so
     * unrecognised values are deliberately ignored rather than guessed at.
     */
    fun derivedState(): HookDerivedState? = when (eventName) {
        "SessionStart" -> HookDerivedState.IDLE
        "SessionEnd" -> HookDerivedState.ENDED
        "UserPromptSubmit" -> HookDerivedState.RUNNING
        // A stop hook asked Claude to keep going, so the turn is still in flight.
        "Stop" -> if (stopHookActive) HookDerivedState.RUNNING else HookDerivedState.IDLE
        "Notification" -> when (notificationType) {
            in BLOCKING_NOTIFICATIONS -> HookDerivedState.NEEDS_INPUT
            // "Your turn" rather than "I am blocked" — the same thing the pid file calls idle.
            "idle_prompt", "agent_completed" -> HookDerivedState.IDLE
            else -> null
        }
        else -> null
    }

    /** True when Claude cannot make progress until you answer. */
    fun blocksOnUser(): Boolean =
        eventName == "Notification" && notificationType in BLOCKING_NOTIFICATIONS

    companion object {
        /** Notifications that mean Claude cannot continue without you. */
        private val BLOCKING_NOTIFICATIONS = setOf(
            "permission_prompt",
            "agent_needs_input",
            "worker_permission_prompt",
            "elicitation_dialog",
            "elicitation_url_dialog",
        )

        /** Events worth installing hooks for; anything else would just add log volume. */
        val SUBSCRIBED_EVENTS = listOf(
            "SessionStart",
            "SessionEnd",
            "UserPromptSubmit",
            "Stop",
            "Notification",
        )
    }
}
