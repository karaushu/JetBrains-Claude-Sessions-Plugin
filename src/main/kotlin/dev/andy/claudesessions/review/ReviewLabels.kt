package dev.andy.claudesessions.review

import dev.andy.claudesessions.ui.UiText

/**
 * Every piece of text the review UI shows, as pure functions.
 *
 * Worth its own file because the send button's label is the last thing the user reads before a
 * round leaves for a session that cannot be recalled. It must always name the target, and it
 * must never grow past the width a toolbar will give it.
 */
internal object ReviewLabels {

    /**
     * `Send 3 notes → Auth flow ▾`, or why there is nothing to send.
     *
     * [openCount] counts every note that is not closed, [count] only the ones a round would take.
     * The two differ once a round is in flight, and saying "nothing new" there is the difference
     * between a button that looks broken and one that explains itself.
     */
    fun sendButton(count: Int, targetTitle: String?, openCount: Int = count): String = when {
        count == 0 && openCount == 0 -> "No review notes"
        count == 0 -> "Nothing new to send"
        targetTitle == null -> "Send ${notes(count)} — no session open"
        // No arrow in the label: the arrow is its own button next to this one.
        else -> "Send ${notes(count)} → ${UiText.oneLine(targetTitle, MAX_TITLE)}"
    }

    /**
     * The same button squeezed into the tool window toolbar, where the full label crowded out the
     * usage percentage: the icon plus a count, with everything else in the tooltip. Empty rather
     * than "No review notes" when there is nothing — the icon alone is enough to keep its place.
     */
    fun sendButtonCompact(count: Int): String = if (count == 0) "" else count.toString()

    fun sendDescription(count: Int, targetTitle: String?): String = when {
        count == 0 -> "Hover a line in a diff, click + to write a note, then send them from here"
        targetTitle == null -> "Open a Claude session to send these notes to"
        else -> "Send ${notes(count)} written in diff gutters to $targetTitle"
    }

    fun notes(count: Int): String = if (count == 1) "1 note" else "$count notes"

    /** Above the box for a new note, so the block is obviously ours and not a stretch of code. */
    fun draftHeader(line: Int): String = "Note for Claude · line ${line + 1}"

    fun threadHeader(thread: ReviewThread): String =
        if (thread.anchorLost) "Note · line no longer found"
        else "Note · line ${thread.anchor.line + 1}"

    fun author(comment: ReviewComment): String =
        if (comment.author == CommentAuthor.USER) "You" else "Claude"

    /** The line under a thread that says where it stands. */
    fun status(thread: ReviewThread, targetTitle: String?): String = when {
        thread.problem == ProtocolProblem.SESSION_GONE ->
            "The session ended without answering this"
        thread.problem == ProtocolProblem.NO_REPLY ->
            "Claude finished without answering this"
        thread.status == ReviewThreadStatus.SENT ->
            "Sent to ${UiText.oneLine(targetTitle ?: "Claude", MAX_TITLE)} · waiting"
        thread.status == ReviewThreadStatus.ANSWERED -> answered(thread)
        else -> "Not sent yet"
    }

    private fun answered(thread: ReviewThread): String {
        val reply = thread.comments.lastOrNull { it.author == CommentAuthor.AGENT }
        val outcome = when (reply?.outcome) {
            ReplyOutcome.SKIPPED -> "Claude skipped this"
            ReplyOutcome.FAILED -> "Claude could not do this"
            ReplyOutcome.QUESTION -> "Claude has a question"
            else -> "Claude answered"
        }
        val files = reply?.touchedFiles?.takeIf { it.isNotEmpty() }
            ?.let { " · ${it.joinToString(", ")}" } ?: ""
        return outcome + files
    }

    private const val MAX_TITLE = 28
}
