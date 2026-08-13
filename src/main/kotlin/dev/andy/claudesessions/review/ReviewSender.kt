package dev.andy.claudesessions.review

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import dev.andy.claudesessions.data.SessionStore
import dev.andy.claudesessions.hooks.HookEventBus
import dev.andy.claudesessions.model.SessionState
import dev.andy.claudesessions.terminal.ClaudeTerminalLauncher
import dev.andy.claudesessions.terminal.ClaudeTerminalTabs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText

/**
 * Hands a batch of review notes to one session.
 *
 * The order of the steps is the interesting part. `sendText` buffers until the shell is ready
 * and never reports delivery, so there is no later moment that means "the agent has it". The
 * notes are therefore marked as sent *before* the line is typed, and rolled back if typing
 * failed outright — marking afterwards would leave a window in which the agent holds a review
 * the plugin is not watching for replies to.
 */
@Service(Service.Level.PROJECT)
internal class ReviewSender(private val project: Project, private val scope: CoroutineScope) {

    private val store get() = project.service<ReviewStore>()

    /** One send at a time, so a double click cannot mint two rounds for the same notes. */
    private val sending = AtomicBoolean(false)

    /** The session the button names, or null when there is nothing open to send to. */
    fun resolveTarget(): String? {
        val tabs = project.service<ClaudeTerminalTabs>()
        return ReviewTargets.resolve(
            openSessionIds = tabs.openSessionIds(),
            focusOrder = tabs.focusedSessionIds(),
            chosen = store.targetSessionId.value,
        )
    }

    fun titleOf(sessionId: String?): String? {
        if (sessionId == null) return null
        val fromStore = project.service<SessionStore>().items.value
            .firstOrNull { it.sessionId == sessionId }?.summary?.title
        return fromStore ?: service<HookEventBus>().titleFor(sessionId)
    }

    /** What would happen if the user pressed Send now, without sending anything. */
    fun gate(sessionId: String? = resolveTarget()): SendGate {
        val item = sessionId?.let { id ->
            project.service<SessionStore>().items.value.firstOrNull { it.sessionId == id }
        }
        val hookState = sessionId?.let { service<HookEventBus>().states()[it] }
        return ReviewGate.evaluate(
            sendableCount = store.sendableThreads().size,
            sessionId = sessionId,
            rawTitle = titleOf(sessionId),
            tabOpen = sessionId != null &&
                project.service<ClaudeTerminalTabs>().find(sessionId) != null,
            state = item?.state?.takeIf { it != SessionState.HISTORICAL } ?: hookState,
            liveStatus = item?.live?.status,
        )
    }

    /**
     * Writes the round and types the prompt. [confirmed] carries the user's answer to a
     * warning the gate raised, and never overrides a refusal the user cannot sensibly waive.
     */
    fun send(sessionId: String, confirmed: Boolean = false, onResult: (SendResult) -> Unit) {
        if (!sending.compareAndSet(false, true)) return
        scope.launch {
            val result = runCatching { perform(sessionId, confirmed) }
                .onFailure { thisLogger().warn("Review send failed", it) }
                .getOrElse { SendResult.Refused(SendGate.SendFailed(it.message ?: "unknown error")) }
            sending.set(false)
            withContext(Dispatchers.EDT) { onResult(result) }
        }
    }

    private suspend fun perform(sessionId: String, confirmed: Boolean): SendResult {
        val gate = gate(sessionId)
        if (!gate.isReady && !(confirmed && gate.needsConfirmation && gate !is SendGate.TabClosed)) {
            return SendResult.Refused(gate)
        }

        val threads = store.sendableThreads()
        if (threads.isEmpty()) return SendResult.Refused(SendGate.NothingToSend)

        val basePath = project.basePath ?: return SendResult.Refused(
            SendGate.WriteFailed("the project has no directory on disk"),
        )
        val now = System.currentTimeMillis()
        val round = ReviewRound(
            id = ReviewPaths.newRoundId(now, kotlin.random.Random.nextInt()),
            sessionId = sessionId,
            startedAtMillis = now,
        )
        val reviewFile = ReviewPaths.reviewFile(basePath, round.id)
        val repliesFile = ReviewPaths.repliesFile(basePath, round.id)

        val written = withContext(Dispatchers.IO) {
            write(reviewFile, ReviewFile.render(threads, repliesFile))
        }
        written?.let { return SendResult.Refused(SendGate.WriteFailed(it)) }

        store.markSent(threads.map { it.id }, round, now)
        store.chooseTarget(sessionId)
        // The round is now the agent's business too, so what we know about it goes to disk before
        // anything else can go wrong.
        ReviewPersistence.scheduleSave(project)

        val prompt = ReviewPrompt.forRound(reviewFile, threads.size)
        val sent = withContext(Dispatchers.EDT) {
            ClaudeTerminalLauncher.sendToSession(project, sessionId, prompt)
        }
        if (!sent) {
            store.rollbackSend(threads.map { it.id }, round.id)
            runCatching { Files.deleteIfExists(reviewFile) }
            return SendResult.Refused(SendGate.SendFailed("its tab is gone"))
        }

        withContext(Dispatchers.IO) { ReviewPaths.prune(reviewFile.parent, now) }
        return SendResult.Sent(threads.size, ReviewTargets.title(titleOf(sessionId)), round.id)
    }

    /** Returns null on success, or the reason to report. */
    private fun write(file: Path, text: String): String? = runCatching {
        file.createParentDirectories()
        file.writeText(text)
        null
    }.getOrElse { it.message ?: it::class.simpleName ?: "unknown error" }
}

internal sealed interface SendResult {

    data class Sent(val count: Int, val title: String, val roundId: String) : SendResult

    data class Refused(val gate: SendGate) : SendResult
}
