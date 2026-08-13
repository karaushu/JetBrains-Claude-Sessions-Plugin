package dev.andy.claudesessions.review

import com.intellij.util.xmlb.annotations.XCollection

/**
 * The wire format [ReviewStore] persists, and the conversion to and from the domain shapes.
 *
 * A separate layer exists because `com.intellij.util.xmlb` instantiates state through a no-arg
 * constructor and writes fields by reflection, which rules out the immutable data classes the
 * rest of the feature is built from. Two rules keep that reflection working:
 *
 * - The bean classes are declared without `internal`. Kotlin mangles the accessors of internal
 *   members with a module suffix, and xmlb would not find them. `ClaudeSessionsSettings.State`
 *   has the same shape for the same reason.
 * - Enums are stored as their `name`, not as enum-typed fields. A value written by a later
 *   plugin version — or by a hand-edited `workspace.xml` — must degrade to a sane default
 *   rather than throw inside `loadState`, which would discard the whole component.
 */
internal object ReviewBeans {

    class State {
        var nextThreadOrdinal: Int = 1

        var lastTargetSessionId: String? = null

        @get:XCollection(style = XCollection.Style.v2)
        var threads: MutableList<ThreadBean> = mutableListOf()

        @get:XCollection(style = XCollection.Style.v2)
        var rounds: MutableList<RoundBean> = mutableListOf()
    }

    class ThreadBean {
        var id: String = ""
        var path: String = ""
        var line: Int = 0
        var lineText: String = ""

        @get:XCollection(style = XCollection.Style.v2)
        var contextLines: MutableList<String> = mutableListOf()

        var contextStartLine: Int = 0
        var languageId: String? = null
        var status: String = ReviewThreadStatus.OPEN.name
        var createdAtMillis: Long = 0
        var roundId: String? = null
        var sentToSessionId: String? = null
        var sentAtMillis: Long? = null
        var awaitingReplyTo: String? = null
        var anchorLost: Boolean = false
        var problem: String? = null

        @get:XCollection(style = XCollection.Style.v2)
        var comments: MutableList<CommentBean> = mutableListOf()
    }

    class CommentBean {
        var id: String = ""
        var author: String = CommentAuthor.USER.name
        var text: String = ""
        var createdAtMillis: Long = 0
        var outcome: String? = null

        @get:XCollection(style = XCollection.Style.v2)
        var touchedFiles: MutableList<String> = mutableListOf()
    }

    class RoundBean {
        var id: String = ""
        var sessionId: String = ""
        var startedAtMillis: Long = 0
        var repliesOffset: Long = 0
        var closed: Boolean = false
    }

    /** What one `loadState` produced, after pruning and after id counters were repaired. */
    class Loaded(
        val threads: List<ReviewThread>,
        val rounds: List<ReviewRound>,
        val nextThreadOrdinal: Int,
        val lastTargetSessionId: String?,
    )

    fun load(state: State, nowMillis: Long): Loaded {
        val threads = state.threads.mapNotNull { thread(it) }.let { prune(it, nowMillis) }
        val rounds = state.rounds.mapNotNull { round(it) }
            .filter { !it.closed || nowMillis - it.startedAtMillis <= ROUND_KEEP_MS }
        return Loaded(
            threads = threads,
            rounds = rounds,
            nextThreadOrdinal = repairOrdinal(state.nextThreadOrdinal, threads),
            lastTargetSessionId = state.lastTargetSessionId?.takeIf { it.isNotBlank() },
        )
    }

    fun save(
        threads: List<ReviewThread>,
        rounds: List<ReviewRound>,
        nextThreadOrdinal: Int,
        lastTargetSessionId: String?,
    ): State = State().apply {
        this.nextThreadOrdinal = nextThreadOrdinal
        this.lastTargetSessionId = lastTargetSessionId
        this.threads = threads.mapTo(mutableListOf()) { bean(it) }
        this.rounds = rounds.mapTo(mutableListOf()) { bean(it) }
    }

    /**
     * The counter is trusted only as far as the data allows: a truncated or hand-edited file
     * that let an id be minted twice would silently merge two threads when a reply arrived.
     */
    fun repairOrdinal(persisted: Int, threads: List<ReviewThread>): Int {
        val highest = threads.mapNotNull { it.id.removePrefix(THREAD_PREFIX).toIntOrNull() }
            .maxOrNull() ?: 0
        return maxOf(persisted, highest + 1, 1)
    }

    /**
     * Keeps `workspace.xml` from growing without bound. Only finished threads are ever dropped:
     * an open or in-flight note is something the user is still waiting on.
     */
    private fun prune(threads: List<ReviewThread>, nowMillis: Long): List<ReviewThread> {
        val kept = threads.filterNot {
            it.status == ReviewThreadStatus.CLOSED &&
                nowMillis - it.createdAtMillis > CLOSED_KEEP_MS
        }
        if (kept.size <= MAX_THREADS) return kept
        val surplus = kept.size - MAX_THREADS
        val dropped = kept.asSequence()
            .filter { it.status == ReviewThreadStatus.CLOSED }
            .sortedBy { it.createdAtMillis }
            .take(surplus)
            .map { it.id }
            .toSet()
        return kept.filterNot { it.id in dropped }
    }

    private fun thread(bean: ThreadBean): ReviewThread? = runCatching {
        if (bean.id.isBlank() || bean.path.isBlank()) return null
        ReviewThread(
            id = bean.id,
            anchor = ReviewAnchor(
                path = bean.path,
                line = bean.line.coerceAtLeast(0),
                lineText = bean.lineText,
                contextLines = bean.contextLines.toList(),
                contextStartLine = bean.contextStartLine.coerceAtLeast(0),
                languageId = bean.languageId,
            ),
            status = enumOrDefault(bean.status, ReviewThreadStatus.OPEN),
            comments = bean.comments.mapNotNull { comment(it) },
            createdAtMillis = bean.createdAtMillis,
            roundId = bean.roundId,
            sentToSessionId = bean.sentToSessionId,
            sentAtMillis = bean.sentAtMillis,
            awaitingReplyTo = bean.awaitingReplyTo,
            anchorLost = bean.anchorLost,
            problem = bean.problem?.let { enumOrNull<ProtocolProblem>(it) },
        )
    }.getOrNull()

    private fun comment(bean: CommentBean): ReviewComment? {
        if (bean.id.isBlank()) return null
        return ReviewComment(
            id = bean.id,
            author = enumOrDefault(bean.author, CommentAuthor.USER),
            text = bean.text,
            createdAtMillis = bean.createdAtMillis,
            outcome = bean.outcome?.let { enumOrNull<ReplyOutcome>(it) },
            touchedFiles = bean.touchedFiles.toList(),
        )
    }

    private fun round(bean: RoundBean): ReviewRound? {
        if (bean.id.isBlank() || bean.sessionId.isBlank()) return null
        return ReviewRound(
            id = bean.id,
            sessionId = bean.sessionId,
            startedAtMillis = bean.startedAtMillis,
            repliesOffset = bean.repliesOffset.coerceAtLeast(0),
            closed = bean.closed,
        )
    }

    private fun bean(thread: ReviewThread): ThreadBean = ThreadBean().apply {
        id = thread.id
        path = thread.anchor.path
        line = thread.anchor.line
        lineText = thread.anchor.lineText
        contextLines = thread.anchor.contextLines.toMutableList()
        contextStartLine = thread.anchor.contextStartLine
        languageId = thread.anchor.languageId
        status = thread.status.name
        createdAtMillis = thread.createdAtMillis
        roundId = thread.roundId
        sentToSessionId = thread.sentToSessionId
        sentAtMillis = thread.sentAtMillis
        awaitingReplyTo = thread.awaitingReplyTo
        anchorLost = thread.anchorLost
        problem = thread.problem?.name
        comments = thread.comments.mapTo(mutableListOf()) { bean(it) }
    }

    private fun bean(comment: ReviewComment): CommentBean = CommentBean().apply {
        id = comment.id
        author = comment.author.name
        text = comment.text
        createdAtMillis = comment.createdAtMillis
        outcome = comment.outcome?.name
        touchedFiles = comment.touchedFiles.toMutableList()
    }

    private fun bean(round: ReviewRound): RoundBean = RoundBean().apply {
        id = round.id
        sessionId = round.sessionId
        startedAtMillis = round.startedAtMillis
        repliesOffset = round.repliesOffset
        closed = round.closed
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name.trim().uppercase() }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, fallback: T): T =
        enumOrNull<T>(name) ?: fallback

    const val THREAD_PREFIX = "C"

    private const val MAX_THREADS = 500

    private const val CLOSED_KEEP_MS = 14 * 24 * 60 * 60 * 1000L

    private const val ROUND_KEEP_MS = 7 * 24 * 60 * 60 * 1000L
}
