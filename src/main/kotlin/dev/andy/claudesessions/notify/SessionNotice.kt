package dev.andy.claudesessions.notify

import dev.andy.claudesessions.hooks.HookEvent
import dev.andy.claudesessions.ui.UiText

/** Which of the two independently configurable kinds of notification this is. */
internal enum class NoticeKind {
    /** Claude finished a turn, or a background agent completed. */
    TURN_END,

    /** Claude cannot continue until you answer. */
    NEEDS_INPUT,
}

/**
 * A notification about to be shown, resolved down to plain text.
 *
 * Titles and Claude's own messages are model output, so everything here has already been
 * through [UiText.oneLine] — a title containing a newline would otherwise break an OS
 * notification into shapes it was not meant to take.
 */
internal data class SessionNotice(
    val kind: NoticeKind,
    val sessionId: String,
    val cwd: String?,
    /** What to call the session. Kept apart from the wording so the tab can be named too. */
    val sessionName: String,
    /** Bold first line. */
    val title: String,
    /** The rest. Empty when there is nothing more worth saying. */
    val body: String,
)

/**
 * Turns hook events into notifications.
 *
 * Kept free of the platform so the rules are testable: which events are worth announcing is
 * the part that is easy to get subtly wrong, and it is not observable in a screenshot.
 */
internal object SessionNotices {

    /** Longest excerpt of Claude's closing message to quote. */
    private const val MESSAGE_EXCERPT = 120

    /**
     * The notification for [event], or null if it is not worth announcing.
     *
     * [sessionName] is what to call the session — Claude's own title where known, otherwise
     * whatever the caller can work out. Only used for display.
     */
    fun from(
        event: HookEvent,
        sessionName: String?,
        notifyOnTurnEnd: Boolean,
        notifyOnPrompt: Boolean,
    ): SessionNotice? {
        val name = UiText.oneLine(sessionName).ifEmpty { "Claude session" }

        return when {
            // A turn ended. `stop_hook_active` means a stop hook sent Claude back to work, so
            // the turn is still running and announcing it would be a lie.
            event.eventName == "Stop" && !event.stopHookActive ->
                if (!notifyOnTurnEnd) null else turnEnd(event, name)

            // A background agent finished. The user's word for these is "agents", and they
            // are exactly the case where nobody is watching the terminal.
            event.eventName == "Notification" && event.notificationType == "agent_completed" ->
                if (!notifyOnTurnEnd) null else turnEnd(event, name)

            event.blocksOnUser() ->
                if (!notifyOnPrompt) null else needsInput(event, name)

            // `idle_prompt` fires a minute after Claude stopped, so it says nothing that the
            // turn-end notification has not already said.
            else -> null
        }
    }

    private fun turnEnd(event: HookEvent, name: String): SessionNotice = SessionNotice(
        kind = NoticeKind.TURN_END,
        sessionId = event.sessionId,
        cwd = event.cwd,
        sessionName = name,
        title = "Claude finished · $name",
        // Claude's closing words say more about what happened than any summary of ours.
        body = UiText.oneLine(event.lastAssistantMessage, MESSAGE_EXCERPT),
    )

    private fun needsInput(event: HookEvent, name: String): SessionNotice = SessionNotice(
        kind = NoticeKind.NEEDS_INPUT,
        sessionId = event.sessionId,
        cwd = event.cwd,
        sessionName = name,
        // Claude distinguishes a permission prompt from a plan approval from an elicitation,
        // and says which in `message`. Wording our own would only lose that.
        title = UiText.oneLine(event.message).ifEmpty { "Claude needs your input" },
        body = name,
    )
}
