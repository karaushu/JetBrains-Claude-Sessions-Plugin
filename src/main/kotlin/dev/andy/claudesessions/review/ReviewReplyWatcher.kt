package dev.andy.claudesessions.review

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import dev.andy.claudesessions.data.SessionStore
import dev.andy.claudesessions.hooks.HookEventBus
import dev.andy.claudesessions.model.SessionState
import dev.andy.claudesessions.terminal.ClaudeTerminalTabs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads what the agent wrote back, and decides when it has stopped writing.
 *
 * The poll loop is gated on there being an in-flight round at all, which is a better switch
 * than counting subscribers: replies must keep landing while the user is looking at some other
 * tab, or has closed the diff entirely. With no round open this costs one `delay` every two
 * seconds and touches no file.
 *
 * Deciding that a round is over is the delicate part, and it is deliberately not a bare timer.
 * A round is finished when the target session has been observed working and has since gone
 * quiet for a few ticks — which usually fires within a second or two of the turn ending — with a
 * long timeout only as a fallback for sessions whose hooks are not installed. A final read runs
 * before the round closes, so a reply written in the same instant is not lost to the race.
 */
@Service(Service.Level.PROJECT)
internal class ReviewReplyWatcher(private val project: Project, scope: CoroutineScope) {

    private val store get() = project.service<ReviewStore>()

    private val hooks get() = service<HookEventBus>()

    private val logs = HashMap<String, ReviewReplyLog>()

    /** Rounds whose session was seen working, so going quiet now means something. */
    private val sawWorking = HashSet<String>()

    private val quietTicks = HashMap<String, Int>()

    /** Ids the agent replied about that belong to no note, reported once when the round ends. */
    private val unknownIds = HashMap<String, MutableSet<String>>()

    private val answered = HashMap<String, Int>()

    init {
        scope.launch { watch() }
    }

    private suspend fun watch() {
        var retained = false
        try {
            while (currentCoroutineContext().isActive) {
                val rounds = store.openRounds()
                if (rounds.isEmpty()) {
                    if (retained) {
                        hooks.release()
                        retained = false
                    }
                    delay(IDLE_TICK_MS)
                    continue
                }
                // Knowing whether the session is still working needs the hook tail running.
                if (!retained) {
                    hooks.retain()
                    retained = true
                }
                rounds.forEach { round ->
                    drain(round)
                    if (isOver(round)) finish(round)
                }
                delay(TICK_MS)
            }
        } finally {
            if (retained) hooks.release()
        }
    }

    private suspend fun drain(round: ReviewRound) {
        val basePath = project.basePath ?: return
        val log = logs.getOrPut(round.id) {
            ReviewReplyLog(ReviewPaths.repliesFile(basePath, round.id), round.repliesOffset)
        }
        val replies = withContext(Dispatchers.IO) { log.readNew() }
        if (log.offset != round.repliesOffset) store.noteRepliesOffset(round.id, log.offset)
        if (replies.isEmpty()) return

        for (reply in replies) {
            // A line that names a round we know is not this one is not ours to apply.
            if (reply.roundId != null && reply.roundId != round.id &&
                store.openRounds().none { it.id == reply.roundId }
            ) {
                continue
            }
            when (val match = store.applyReply(reply)) {
                is ReplyMatch.Applied -> {
                    answered[round.id] = (answered[round.id] ?: 0) + 1
                    // An answer is the agent's work; it should survive a crash the same way the
                    // question does.
                    ReviewPersistence.scheduleSave(project)
                }
                is ReplyMatch.Unknown ->
                    unknownIds.getOrPut(round.id) { mutableSetOf() } += match.commentId
                ReplyMatch.Duplicate -> Unit
            }
        }
    }

    /**
     * Whether nothing more will arrive: the session went quiet after working, it is no longer
     * there at all, or the fallback timeout ran out.
     */
    private fun isOver(round: ReviewRound): Boolean {
        // Nothing awaited does not yet mean nothing more will come: a follow-up typed on the
        // round's only thread re-queues it and clears its debt while the agent is still
        // mid-answer. Hold the round while the session works, so that answer lands in the
        // thread instead of being orphaned in a file nobody polls.
        if (store.awaitedCommentIds(round.id).isEmpty() &&
            stateOf(round.sessionId) != SessionState.RUNNING
        ) {
            return true
        }
        if (System.currentTimeMillis() - round.startedAtMillis > SILENCE_TIMEOUT_MS) return true
        if (sessionGone(round)) return true

        return if (stateOf(round.sessionId) == SessionState.RUNNING) {
            sawWorking += round.id
            quietTicks[round.id] = 0
            false
        } else if (round.id in sawWorking) {
            val ticks = (quietTicks[round.id] ?: 0) + 1
            quietTicks[round.id] = ticks
            ticks >= QUIET_TICKS
        } else {
            false
        }
    }

    /** No tab and no live process: whatever the agent was doing, it is not doing it here. */
    private fun sessionGone(round: ReviewRound): Boolean {
        if (project.service<ClaudeTerminalTabs>().find(round.sessionId) != null) return false
        return project.service<SessionStore>().items.value
            .none { it.sessionId == round.sessionId && it.isLive }
    }

    private fun stateOf(sessionId: String): SessionState? =
        project.service<SessionStore>().effectiveState(sessionId)

    private suspend fun finish(round: ReviewRound) {
        val gone = sessionGone(round)
        drain(round)

        val stranded = store.threads.value
            .filter { it.roundId == round.id && it.awaitingReplyTo != null }
        if (stranded.isNotEmpty()) {
            val problem = if (gone) ProtocolProblem.SESSION_GONE else ProtocolProblem.NO_REPLY
            store.flagProblem(stranded.map { it.id }, problem)
        }

        val notice = ReviewNotices.forRound(
            answered = answered[round.id] ?: 0,
            unanswered = stranded.size,
            unknownIds = unknownIds[round.id]?.toList() ?: emptyList(),
            sessionTitle = ReviewTargets.title(project.service<ReviewSender>().titleOf(round.sessionId)),
            sessionGone = gone,
        )
        store.closeRound(round.id)
        ReviewPersistence.scheduleSave(project)
        logs.remove(round.id)
        sawWorking.remove(round.id)
        quietTicks.remove(round.id)
        answered.remove(round.id)
        val unknown = unknownIds.remove(round.id)

        if (notice != null) {
            withContext(Dispatchers.EDT) { show(notice, round, stranded.map { it.id }) }
        } else {
            thisLogger().debug("Review round ${round.id} closed with every comment answered")
        }
        if (unknown != null) {
            thisLogger().debug("Review round ${round.id} had replies for unknown ids: $unknown")
        }
    }

    private fun show(notice: ReviewNotice, round: ReviewRound, strandedIds: List<String>) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            .createNotification(notice.title, notice.body, NotificationType.INFORMATION)
            .setDisplayId("claude.review.${notice.kind}")

        if (strandedIds.isNotEmpty()) {
            notification.addAction(
                NotificationAction.createSimpleExpiring("Queue Them Again") {
                    store.rollbackSend(strandedIds, round.id)
                },
            )
        }
        notification.addAction(
            NotificationAction.createSimpleExpiring("Open Replies File") {
                openReplies(round)
            },
        )
        notification.notify(project)
    }

    /** The raw truth, in case the plugin's account of what happened is not believed. */
    private fun openReplies(round: ReviewRound) {
        val basePath = project.basePath ?: return
        val path = ReviewPaths.repliesFile(basePath, round.id)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        if (file == null) {
            thisLogger().info("Claude wrote no replies file for round ${round.id}")
            return
        }
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private companion object {
        const val TICK_MS = 500L

        const val IDLE_TICK_MS = 2_000L

        /** Three quiet ticks after working, so a pause between tool calls is not the end. */
        const val QUIET_TICKS = 3

        /** For a session with no hooks installed, the only thing left is to give up eventually. */
        const val SILENCE_TIMEOUT_MS = 30 * 60 * 1000L

        const val GROUP = "Claude Sessions"
    }
}
