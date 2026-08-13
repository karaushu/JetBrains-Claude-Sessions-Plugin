package dev.andy.claudesessions.review

/**
 * A review note the user wrote against one line of a diff, and everything said about it since.
 *
 * The shapes here are deliberately free of platform imports. They are the value the diff UI
 * renders, the sender serialises and the reply watcher mutates, so keeping them plain makes
 * every state transition assertable in a unit test without an IDE around them.
 *
 * Persistence is a separate concern: [ReviewStore] converts these to and from mutable beans,
 * because `XmlSerializerUtil` cannot instantiate a Kotlin data class.
 */
internal data class ReviewThread(
    val id: String,
    val anchor: ReviewAnchor,
    val status: ReviewThreadStatus,
    val comments: List<ReviewComment>,
    val createdAtMillis: Long,
    /** The round this thread last went out in, or null if it has never been sent. */
    val roundId: String? = null,
    val sentToSessionId: String? = null,
    val sentAtMillis: Long? = null,
    /**
     * The user comment the agent still owes an answer to. Set while [ReviewThreadStatus.SENT]
     * and cleared as soon as a reply lands, so "answered" can never be inferred from the mere
     * presence of an agent comment left over from an earlier round.
     */
    val awaitingReplyTo: String? = null,
    /** The line this thread points at no longer exists; see [ReviewAnchoring]. */
    val anchorLost: Boolean = false,
    val problem: ProtocolProblem? = null,
) {

    val lastUserCommentId: String?
        get() = comments.lastOrNull { it.author == CommentAuthor.USER }?.id

    /** Only an open thread joins the next round. Everything else is either in flight or done. */
    val isSendable: Boolean get() = status == ReviewThreadStatus.OPEN

    /**
     * Appends what the user typed and puts the thread back in the outgoing queue.
     *
     * This is the one transition that reopens a thread, whether it was answered, closed or
     * merely waiting — writing on a line again is how the user says "not yet".
     */
    fun withUserComment(id: String, text: String, atMillis: Long): ReviewThread = copy(
        status = ReviewThreadStatus.OPEN,
        comments = comments + ReviewComment(id, CommentAuthor.USER, text, atMillis),
        awaitingReplyTo = null,
        problem = null,
    )

    fun markedSent(roundId: String, sessionId: String, atMillis: Long): ReviewThread = copy(
        status = ReviewThreadStatus.SENT,
        roundId = roundId,
        sentToSessionId = sessionId,
        sentAtMillis = atMillis,
        awaitingReplyTo = lastUserCommentId,
        problem = null,
    )

    /** Undoes [markedSent] after a failed send. The comments themselves are untouched. */
    fun rolledBack(): ReviewThread = copy(
        status = ReviewThreadStatus.OPEN,
        roundId = null,
        sentToSessionId = null,
        sentAtMillis = null,
        awaitingReplyTo = null,
    )

    /**
     * Attaches what the agent reported.
     *
     * A reply is never discarded, not even for a thread the user closed in the meantime: the
     * agent did the work, and hiding its account of it would leave the user guessing. What
     * varies is only the status the thread lands in, and whether that counts as a protocol
     * problem worth surfacing.
     */
    fun withAgentReply(comment: ReviewComment): ReviewThread {
        val answered = comment.id == awaitingReplyTo
        return copy(
            status = when {
                status == ReviewThreadStatus.CLOSED -> ReviewThreadStatus.CLOSED
                answered || status == ReviewThreadStatus.SENT -> ReviewThreadStatus.ANSWERED
                else -> status
            },
            comments = comments + comment,
            awaitingReplyTo = if (answered) null else awaitingReplyTo,
            problem = when {
                status == ReviewThreadStatus.CLOSED -> ProtocolProblem.UNEXPECTED_REPLY
                answered -> null
                else -> ProtocolProblem.LATE_REPLY
            },
        )
    }

    fun closed(): ReviewThread = copy(status = ReviewThreadStatus.CLOSED, awaitingReplyTo = null)

    fun withProblem(problem: ProtocolProblem): ReviewThread = copy(problem = problem)

    /** Moves the note to where its line ended up, or marks it detached when it went away. */
    fun reanchored(line: Int?, lineText: String = anchor.lineText): ReviewThread =
        if (line == null) copy(anchorLost = true)
        else copy(anchor = anchor.copy(line = line, lineText = lineText), anchorLost = false)

    /** True when this reply was already applied, which is what makes a replayed file harmless. */
    fun hasReply(commentId: String, text: String): Boolean =
        comments.any { it.author == CommentAuthor.AGENT && it.id == commentId && it.text == text }
}

internal enum class ReviewThreadStatus { OPEN, SENT, ANSWERED, CLOSED }

internal enum class CommentAuthor { USER, AGENT }

/** What the agent said it did. An unrecognised word from the model degrades to [DONE]. */
internal enum class ReplyOutcome { DONE, SKIPPED, FAILED, QUESTION }

/** How a round departed from the reply protocol, when it did. */
internal enum class ProtocolProblem { NO_REPLY, LATE_REPLY, UNEXPECTED_REPLY, SESSION_GONE }

internal data class ReviewComment(
    /** `C7.2` — the thread id, a dot, then an ordinal. The thread id is always a prefix. */
    val id: String,
    val author: CommentAuthor,
    val text: String,
    val createdAtMillis: Long,
    val outcome: ReplyOutcome? = null,
    /** Project-relative paths the agent claims it touched. Agent comments only. */
    val touchedFiles: List<String> = emptyList(),
)

/**
 * Where a note points, and enough of the surrounding code to survive the agent rewriting it.
 *
 * [contextLines] is captured when the note is written rather than read back at send time. That
 * makes rendering the review file a pure function of persisted state: no read action, no EDT
 * hop, and no requirement that the diff still be open when the user presses Send.
 */
internal data class ReviewAnchor(
    /** Project-relative, `/` separated. Never absolute — the agent's paths are relative too. */
    val path: String,
    /** 0-based line in the after side of the diff, which is the file's own line. */
    val line: Int,
    /** The trimmed text of that line, used to find it again after an edit. */
    val lineText: String,
    val contextLines: List<String> = emptyList(),
    /** 0-based line of `contextLines[0]`, so the review file can number the hunk. */
    val contextStartLine: Int = line,
    /** Markdown fence language for the hunk, guessed from the extension. */
    val languageId: String? = null,
)
