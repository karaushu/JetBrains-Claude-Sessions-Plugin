package dev.andy.claudesessions.review

import dev.andy.claudesessions.model.SessionState
import dev.andy.claudesessions.ui.UiText

/**
 * Which sessions a round may be sent to, in the order worth offering them.
 *
 * Pure given its inputs so the ordering rules can be asserted: the button always names the
 * session it would send to, and getting that wrong means a review arriving in the wrong
 * conversation, which cannot be undone.
 */
internal object ReviewTargets {

    /**
     * Ordered most-recently-focused first, then any remaining open tab by id for stability.
     * Only sessions with an open tab appear — a closed one has nothing to type into.
     */
    fun options(
        openSessionIds: Set<String>,
        focusOrder: List<String>,
        titles: Map<String, String?>,
        states: Map<String, SessionState>,
        chosen: String?,
    ): List<TargetOption> {
        val ordered = focusOrder.filter { it in openSessionIds } +
            openSessionIds.filterNot { it in focusOrder }.sorted()
        return ordered.distinct().map { sessionId ->
            TargetOption(
                sessionId = sessionId,
                title = title(titles[sessionId]),
                state = states[sessionId],
                isChosen = sessionId == chosen,
            )
        }
    }

    /**
     * The session the button names by default: the user's own last choice while its tab is
     * still open, otherwise whichever session they looked at most recently.
     */
    fun resolve(openSessionIds: Set<String>, focusOrder: List<String>, chosen: String?): String? {
        if (chosen != null && chosen in openSessionIds) return chosen
        focusOrder.firstOrNull { it in openSessionIds }?.let { return it }
        return openSessionIds.singleOrNull()
    }

    /** Session titles come from the model, so they are sanitised before they reach a button. */
    fun title(raw: String?): String =
        UiText.oneLine(raw?.takeIf { it.isNotBlank() } ?: "Untitled session", MAX_TITLE)

    private const val MAX_TITLE = 40
}

internal data class TargetOption(
    val sessionId: String,
    val title: String,
    /** Drives the icon in the picker, so a busy session is visibly busy before it is chosen. */
    val state: SessionState?,
    val isChosen: Boolean,
)
