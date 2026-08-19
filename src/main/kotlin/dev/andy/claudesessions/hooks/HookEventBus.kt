package dev.andy.claudesessions.hooks

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import dev.andy.claudesessions.data.LiveSessionWatcher
import dev.andy.claudesessions.model.SessionState
import dev.andy.claudesessions.settings.ClaudeSessionsSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * The single reader of the hook event log.
 *
 * Application-level for two reasons. The log is one file under `~/.claude` shared by every
 * project, so one tail is all that is correct: two readers would each reset to the start when
 * the other truncated, and replay each other's events. And with several projects open, a
 * per-project reader would announce the same session finishing once per window.
 *
 * Unlike the session list, this runs whether or not the tool window is visible — a
 * notification that only arrived while you were looking at the list would be pointless. The
 * cost of that is one `stat` per tick, so it is affordable to leave running; it is still
 * skipped entirely when nothing is subscribed and no notification is switched on.
 */
@Service(Service.Level.APP)
internal class HookEventBus(private val scope: CoroutineScope) {

    private val log = HookEventLog()
    private val tracker = HookStatusTracker()
    private val titles = SessionTitles()

    private val settings get() = service<ClaudeSessionsSettings>()

    private val _revision = MutableStateFlow(0)

    /** Bumped whenever a session's hook-derived state changed. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _events = MutableSharedFlow<HookEvent>(
        extraBufferCapacity = 64,
        // A notification nobody consumed in time is not worth stalling the tail for.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Events as they happen. Deliberately excludes what was already in the log when the tail
     * started, or while it was idle: those already happened, and re-announcing a turn that
     * ended before the IDE opened would be worse than saying nothing.
     */
    val events: SharedFlow<HookEvent> = _events.asSharedFlow()

    private val subscribers = AtomicInteger(0)

    /** True once a batch has been folded, so subsequent batches are genuinely new. */
    private var primed = false

    /** Whether the previous tick was watching, to detect being switched back on. */
    private var wasWatching = false

    private val tickInterval = 500L

    init {
        scope.launch { tail() }
    }

    /** Hook-derived state per session, for rows that have no pid file. */
    fun states(): Map<String, SessionState> = tracker.states()

    /** What Claude calls this session, if a hook event has mentioned it. */
    fun titleFor(sessionId: String): String? = titles[sessionId]

    /**
     * Registers interest in [revision] and keeps the tail reading. Balanced by [release].
     *
     * The session list only polls while visible, so without this the tail would go quiet at
     * exactly the moment the list came back and wanted fresh state.
     */
    fun retain() {
        subscribers.incrementAndGet()
    }

    fun release() {
        subscribers.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    private suspend fun tail() {
        while (scope.isActive) {
            val watching = subscribers.get() > 0 || settings.notifiesAnything

            if (watching) {
                // Switched back on after a quiet spell: whatever accumulated meanwhile is
                // history, and folding it would resurrect states that have since moved on.
                if (!wasWatching && primed) withContext(Dispatchers.IO) { log.skipToEnd() }
                poll()
            }

            wasWatching = watching
            delay(tickInterval)
        }
    }

    private suspend fun poll() {
        val events = withContext(Dispatchers.IO) { log.readNew() }

        // Priming is about the first *read*, not the first non-empty one: an empty log at
        // startup would otherwise swallow whatever event finally arrived.
        val wasPriming = !primed
        primed = true
        if (events.isEmpty()) return

        events.forEach(titles::remember)
        if (tracker.apply(events)) _revision.value++

        // The first read is the log as it stood before we were listening.
        if (wasPriming) {
            // That history can span days, and a session that died without a SessionEnd — a
            // crash, a SIGKILL — would sit in the tracker as RUNNING forever. Keep primed
            // state only for sessions that still have a live process behind them.
            val liveIds = withContext(Dispatchers.IO) { LiveSessionWatcher().poll().keys }
            if (tracker.retainAll(liveIds)) _revision.value++
            return
        }
        events.forEach { _events.tryEmit(it) }
    }
}
