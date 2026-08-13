package dev.andy.claudesessions.review

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Every review note in this project, and the rounds they went out in.
 *
 * The one source of truth for the feature: the diff UI only draws what this flow holds, the
 * sender reads a snapshot of it, and the reply watcher writes back into it. Nothing else keeps
 * review state, which is what makes a diff tab closing, a rediff, or an IDE restart uneventful.
 *
 * Stored in `workspace.xml` rather than a file of its own under `.idea`. A note anchors to a
 * line of an uncommitted diff: it is private to this checkout, it is meaningless to a teammate
 * once the branch is merged, and a file that changed on every keystroke would otherwise appear
 * in the commit dialog beside the very changes under review.
 */
@Service(Service.Level.PROJECT)
@State(name = "ClaudeCodeReview", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class ReviewStore : PersistentStateComponent<ReviewBeans.State> {

    private val _threads = MutableStateFlow<List<ReviewThread>>(emptyList())

    /** Sorted by path, then line, so every consumer agrees on order without re-sorting. */
    val threads: StateFlow<List<ReviewThread>> = _threads.asStateFlow()

    private val _rounds = MutableStateFlow<List<ReviewRound>>(emptyList())

    val rounds: StateFlow<List<ReviewRound>> = _rounds.asStateFlow()

    /** The session the user last chose to send to. Survives a restart; re-validated on use. */
    private val _targetSessionId = MutableStateFlow<String?>(null)

    val targetSessionId: StateFlow<String?> = _targetSessionId.asStateFlow()

    private var nextThreadOrdinal = 1

    override fun getState(): ReviewBeans.State = ReviewBeans.save(
        threads = _threads.value,
        rounds = _rounds.value,
        nextThreadOrdinal = nextThreadOrdinal,
        lastTargetSessionId = _targetSessionId.value,
    )

    override fun loadState(state: ReviewBeans.State) {
        val loaded = ReviewBeans.load(state, System.currentTimeMillis())
        _threads.value = loaded.threads.sorted()
        _rounds.value = loaded.rounds
        nextThreadOrdinal = loaded.nextThreadOrdinal
        _targetSessionId.value = loaded.lastTargetSessionId
    }

    fun threadsFor(path: String): List<ReviewThread> =
        _threads.value.filter { it.anchor.path == path && it.status != ReviewThreadStatus.CLOSED }

    fun find(threadId: String): ReviewThread? = _threads.value.firstOrNull { it.id == threadId }

    fun sendableThreads(): List<ReviewThread> = _threads.value.filter { it.isSendable }

    fun chooseTarget(sessionId: String?) {
        _targetSessionId.value = sessionId
    }

    /**
     * Starts a note on a line, or continues the one already there.
     *
     * Continuing rather than stacking a second note is what makes "write on the line again"
     * behave as the user expects: one conversation per line, carrying its history into the next
     * round. Returns the thread id the comment landed in.
     */
    fun addComment(anchor: ReviewAnchor, text: String, nowMillis: Long = now()): String {
        val existing = _threads.value.firstOrNull {
            it.anchor.path == anchor.path &&
                it.anchor.line == anchor.line &&
                it.status != ReviewThreadStatus.CLOSED
        }
        if (existing != null) {
            replyTo(existing.id, text, nowMillis)
            return existing.id
        }

        val id = ReviewBeans.THREAD_PREFIX + nextThreadOrdinal++
        val thread = ReviewThread(
            id = id,
            anchor = anchor,
            status = ReviewThreadStatus.OPEN,
            comments = listOf(ReviewComment("$id.1", CommentAuthor.USER, text, nowMillis)),
            createdAtMillis = nowMillis,
        )
        _threads.update { (it + thread).sorted() }
        return id
    }

    /** Appends a further user comment, reopening the thread whatever state it was in. */
    fun replyTo(threadId: String, text: String, nowMillis: Long = now()): Boolean {
        var changed = false
        _threads.update { threads ->
            threads.map { thread ->
                if (thread.id != threadId) thread
                else {
                    changed = true
                    thread.withUserComment(nextCommentId(thread), text, nowMillis)
                }
            }
        }
        return changed
    }

    fun close(threadId: String) = mutate(threadId) { it.closed() }

    /** Only for a note whose line is gone: the user is throwing away their own writing. */
    fun delete(threadId: String) {
        _threads.update { threads -> threads.filterNot { it.id == threadId } }
    }

    /**
     * Discards every note and every round.
     *
     * The id counter is deliberately not reset: a reply from a round already in flight must not
     * be able to land on a brand-new note that happens to have inherited its id.
     */
    fun clearAll() {
        _threads.value = emptyList()
        _rounds.value = emptyList()
    }

    /**
     * Records a batch as in flight, before the text reaches the terminal.
     *
     * That order is deliberate. `sendText` buffers until the shell is ready and never reports
     * delivery, so there is no later moment that means "the agent has it". Marking first and
     * rolling back on a synchronous failure is the only honest arrangement; marking afterwards
     * would leave a window in which the agent holds a review the plugin is not watching.
     */
    fun markSent(threadIds: Collection<String>, round: ReviewRound, nowMillis: Long = now()) {
        val ids = threadIds.toSet()
        _threads.update { threads ->
            threads.map { thread ->
                if (thread.id in ids) thread.markedSent(round.id, round.sessionId, nowMillis)
                else thread
            }
        }
        _rounds.update { rounds -> rounds.filterNot { it.id == round.id } + round }
    }

    fun rollbackSend(threadIds: Collection<String>, roundId: String) {
        val ids = threadIds.toSet()
        _threads.update { threads ->
            threads.map { thread -> if (thread.id in ids) thread.rolledBack() else thread }
        }
        _rounds.update { rounds -> rounds.filterNot { it.id == roundId } }
    }

    /**
     * Attaches a reply the agent wrote, matching it to a thread as generously as is safe.
     *
     * The id is the only thing tying a reply to a note, so several near-misses are accepted:
     * the awaited comment, any comment the thread ever had, and a bare thread id where the
     * model dropped the ordinal. What is never done is guessing — an id nobody recognises
     * changes nothing and is reported, because a reply shown under the wrong note is worse
     * than a reply the user has to go and read for themselves.
     */
    fun applyReply(reply: ReviewReply, nowMillis: Long = now()): ReplyMatch {
        val target = matchThread(reply.commentId) ?: return ReplyMatch.Unknown(reply.commentId)

        // An id that named the thread rather than the comment answers what the thread awaits.
        val resolvedId = if (reply.commentId == target.id && target.awaitingReplyTo != null) {
            target.awaitingReplyTo
        } else {
            reply.commentId
        }
        if (target.hasReply(resolvedId, reply.summary)) return ReplyMatch.Duplicate

        val comment = ReviewComment(
            id = resolvedId,
            author = CommentAuthor.AGENT,
            text = reply.summary,
            createdAtMillis = nowMillis,
            outcome = reply.outcome,
            touchedFiles = reply.touchedFiles,
        )
        val late = resolvedId != target.awaitingReplyTo
        mutate(target.id) { it.withAgentReply(comment) }
        return ReplyMatch.Applied(target.id, late)
    }

    private fun matchThread(commentId: String): ReviewThread? {
        val threads = _threads.value
        threads.firstOrNull { it.awaitingReplyTo == commentId }?.let { return it }
        threads.firstOrNull { thread -> thread.comments.any { it.id == commentId } }
            ?.let { return it }
        // The model dropped the ordinal and answered with the thread id, which is a prefix.
        return threads.firstOrNull { it.id == commentId }
    }

    fun flagProblem(threadIds: Collection<String>, problem: ProtocolProblem) {
        val ids = threadIds.toSet()
        _threads.update { threads ->
            threads.map { thread ->
                if (thread.id in ids) thread.withProblem(problem) else thread
            }
        }
    }

    /** Applies a whole file's re-anchoring result; see [ReviewAnchoring.anchorAll]. */
    fun reanchor(results: Map<String, Int?>) {
        if (results.isEmpty()) return
        _threads.update { threads ->
            threads.map { thread ->
                if (!results.containsKey(thread.id)) thread
                else {
                    val line = results[thread.id]
                    if (line == thread.anchor.line && !thread.anchorLost) thread
                    else thread.reanchored(line)
                }
            }.sorted()
        }
    }

    /** Rounds still worth reading a replies file for. */
    fun openRounds(): List<ReviewRound> = _rounds.value.filterNot { it.closed }

    fun awaitedCommentIds(roundId: String): List<String> = _threads.value
        .filter { it.roundId == roundId && it.awaitingReplyTo != null }
        .mapNotNull { it.awaitingReplyTo }

    fun noteRepliesOffset(roundId: String, offset: Long) {
        _rounds.update { rounds ->
            rounds.map { if (it.id == roundId) it.copy(repliesOffset = offset) else it }
        }
    }

    fun closeRound(roundId: String) {
        _rounds.update { rounds ->
            rounds.map { if (it.id == roundId) it.copy(closed = true) else it }
        }
    }

    private fun mutate(threadId: String, change: (ReviewThread) -> ReviewThread) {
        _threads.update { threads ->
            threads.map { thread -> if (thread.id == threadId) change(thread) else thread }
        }
    }

    /**
     * Never `comments.size + 1`: pruning or deleting a comment would then hand out an id a
     * reply still in flight is addressed to.
     */
    private fun nextCommentId(thread: ReviewThread): String {
        val highest = thread.comments
            .mapNotNull { it.id.substringAfterLast('.', "").toIntOrNull() }
            .maxOrNull() ?: 0
        return "${thread.id}.${highest + 1}"
    }

    private fun List<ReviewThread>.sorted(): List<ReviewThread> =
        sortedWith(compareBy({ it.anchor.path }, { it.anchor.line }, { it.createdAtMillis }))

    private fun now(): Long = System.currentTimeMillis()
}

/** What became of one reply line. */
internal sealed interface ReplyMatch {

    data class Applied(val threadId: String, val late: Boolean) : ReplyMatch

    /** The same reply was already attached, which is what makes a replayed file harmless. */
    data object Duplicate : ReplyMatch

    data class Unknown(val commentId: String) : ReplyMatch
}
