package dev.andy.claudesessions.review

/**
 * One batch of notes handed to one session.
 *
 * A round exists so replies can be attributed and so the plugin knows where it had got to in
 * the replies file. [repliesOffset] is persisted for exactly that reason: an IDE restarted in
 * the middle of a long review must resume reading rather than re-apply everything — though
 * re-applying is harmless too, because [ReviewThread.hasReply] makes it idempotent.
 */
internal data class ReviewRound(
    /** `20260812-104233-a1b2`: sortable, unique without a counter, survives a crash. */
    val id: String,
    val sessionId: String,
    val startedAtMillis: Long,
    val repliesOffset: Long = 0,
    /**
     * The agent has stopped working and nothing more will arrive, so the file is no longer
     * polled. Closing a round is a decision made from hook state, never from a bare timer.
     */
    val closed: Boolean = false,
)
