package dev.andy.claudesessions.review

import dev.andy.claudesessions.model.SessionState
import dev.andy.claudesessions.ui.UiText

/**
 * Whether a round may be handed to a session, and what to say when it may not.
 *
 * Sending is not reversible. The text lands in a live agent's input, and by the time anything
 * has gone wrong the agent may already have edited files — so every reason to hesitate is
 * decided before the send rather than reported after it.
 */
internal sealed interface SendGate {

    data class Ready(val sessionId: String, val title: String) : SendGate

    /**
     * Sendable, but the session's state is unknown — a session started from Claude Desktop or
     * the SDK writes no status. Worth saying so before typing into it.
     */
    data class Unsure(val sessionId: String, val title: String) : SendGate

    data object NothingToSend : SendGate

    data object NoTarget : SendGate

    data class TabClosed(val sessionId: String, val title: String) : SendGate

    /** Claude is mid-turn. The CLI queues typed text and reads it at an unpredictable point. */
    data class Busy(val sessionId: String, val title: String) : SendGate

    /**
     * Claude is blocked on a question. Refused outright, with no "send anyway": the line would
     * be taken as the answer to a permission prompt or a plan approval, which is not what the
     * user asked for and cannot be taken back.
     */
    data class NeedsInput(val title: String) : SendGate

    /** The session is sitting at a shell prompt, so the line would become `command not found`. */
    data class ShellOnly(val title: String) : SendGate

    data class WriteFailed(val message: String) : SendGate

    data class SendFailed(val message: String) : SendGate

    /** The round can go now, without asking anything. */
    val isReady: Boolean get() = this is Ready

    /** The round can go, but the user should be told what they are sending into first. */
    val needsConfirmation: Boolean get() = this is Unsure || this is Busy || this is TabClosed

    fun problem(): String? = when (this) {
        is Ready -> null
        is Unsure -> "Cannot tell what $title is doing"
        NothingToSend -> "No notes to send"
        NoTarget -> "Open a Claude session first"
        is TabClosed -> "The tab for $title is closed"
        is Busy -> "Claude is working in $title"
        is NeedsInput -> "Claude is waiting for an answer in $title"
        is ShellOnly -> "$title is at a shell prompt, not in Claude"
        is WriteFailed -> "Could not write the review file: $message"
        is SendFailed -> "Could not send to the session: $message"
    }
}

/**
 * Decides the gate from what is known about the target.
 *
 * Pure, so every refusal is assertable without a project: the wording of these is the only
 * thing standing between the user and a review typed into a permission prompt.
 */
internal object ReviewGate {

    fun evaluate(
        sendableCount: Int,
        sessionId: String?,
        rawTitle: String?,
        tabOpen: Boolean,
        state: SessionState?,
        liveStatus: String? = null,
    ): SendGate {
        if (sendableCount == 0) return SendGate.NothingToSend
        if (sessionId == null) return SendGate.NoTarget

        val title = UiText.oneLine(rawTitle?.takeIf { it.isNotBlank() } ?: "this session", MAX_TITLE)
        if (!tabOpen) return SendGate.TabClosed(sessionId, title)
        if (liveStatus == "shell") return SendGate.ShellOnly(title)

        return when (state) {
            SessionState.NEEDS_INPUT -> SendGate.NeedsInput(title)
            SessionState.RUNNING -> SendGate.Busy(sessionId, title)
            SessionState.LIVE_IDLE -> SendGate.Ready(sessionId, title)
            else -> SendGate.Unsure(sessionId, title)
        }
    }

    private const val MAX_TITLE = 40
}
