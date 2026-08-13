package dev.andy.claudesessions.review

/**
 * What to say when a round finished without the agent holding up its end.
 *
 * Kept pure and separate because the wording is the whole point. A note the agent never
 * answered stays visibly unanswered — it is never quietly marked as done — and the balloon has
 * to say so without implying the review failed, because the code was very likely fixed anyway.
 * The reply is commentary; the diff is the deliverable.
 */
internal object ReviewNotices {

    fun forRound(
        answered: Int,
        unanswered: Int,
        unknownIds: List<String>,
        sessionTitle: String,
        sessionGone: Boolean,
    ): ReviewNotice? = when {
        unanswered > 0 && sessionGone -> ReviewNotice(
            kind = "session-gone",
            title = "$sessionTitle ended with ${count(unanswered)} unanswered",
            body = answeredSoFar(answered) +
                "The code may still have been changed — check the diff, then send the notes " +
                "again if you want them picked up.",
        )

        unanswered > 0 -> ReviewNotice(
            kind = "no-reply",
            title = "Claude finished without answering ${count(unanswered)}",
            body = answeredSoFar(answered) +
                "The changes it made are in the diff either way; the notes are still there to " +
                "send again.",
        )

        unknownIds.isNotEmpty() -> ReviewNotice(
            kind = "unknown-id",
            title = "Claude replied about ${count(unknownIds.size)} not in this review",
            body = "Nothing was attached for ${unknownIds.take(MAX_IDS).joinToString(", ")}. " +
                "Open the replies file to see what it wrote.",
        )

        else -> null
    }

    private fun answeredSoFar(answered: Int): String = when (answered) {
        0 -> ""
        1 -> "1 comment was answered. "
        else -> "$answered comments were answered. "
    }

    private fun count(n: Int): String = if (n == 1) "1 comment" else "$n comments"

    private const val MAX_IDS = 5
}

internal data class ReviewNotice(
    /** Keys the display id, so repeats collapse instead of stacking a row per round. */
    val kind: String,
    val title: String,
    val body: String,
)
