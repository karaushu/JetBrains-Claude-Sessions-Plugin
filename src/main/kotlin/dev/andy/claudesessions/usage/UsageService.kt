package dev.andy.claudesessions.usage

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.andy.claudesessions.settings.ClaudeSessionsSettings
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Caches the usage reading so the toolbar can ask for it as often as it likes, and refreshes
 * it on demand by running Claude's own `/usage`.
 */
@Service(Service.Level.APP)
internal class UsageService(private val scope: CoroutineScope) {

    private val reader = UsageReader()
    private val refreshing = AtomicBoolean(false)

    /**
     * Callbacks waiting for the in-flight refresh. A request that loses the [refreshing]
     * race queues here and is answered when that run finishes — dropping it left the
     * popup on "Asking Claude…" forever.
     */
    private val pendingCallbacks = mutableListOf<(UsageSnapshot?, UsageRefreshResult) -> Unit>()

    private fun drainCallbacks(): List<(UsageSnapshot?, UsageRefreshResult) -> Unit> =
        synchronized(pendingCallbacks) {
            val drained = pendingCallbacks.toList()
            pendingCallbacks.clear()
            drained
        }

    @Volatile
    private var cached: UsageSnapshot? = null

    @Volatile
    private var readAtNanos: Long = 0

    /** When a refresh process was last spawned, for gating when there is no snapshot to age. */
    @Volatile
    private var attemptedAtNanos: Long = 0

    private val minRereadNanos = TimeUnit.SECONDS.toNanos(5)

    /** Never call from the EDT: this reads a file. Action updates run on a background thread. */
    fun snapshot(): UsageSnapshot? {
        val now = System.nanoTime()
        if (cached != null && now - readAtNanos < minRereadNanos) return cached
        return reread()
    }

    fun reread(): UsageSnapshot? {
        readAtNanos = System.nanoTime()
        // Keep the previous reading if a read lands mid-write; better than blanking the UI.
        reader.read()?.let { cached = it }
        return cached
    }

    fun cachedSnapshot(): UsageSnapshot? = cached

    fun isRefreshing(): Boolean = refreshing.get()

    /**
     * Asks Claude to refresh the figures, then re-reads them.
     *
     * Coalesced: a second request while one is in flight does not spawn another process —
     * its [onDone] is queued and answered with the in-flight run's result. [onDone] runs
     * on the EDT with the newest reading.
     */
    fun refreshInBackground(onDone: (UsageSnapshot?, UsageRefreshResult) -> Unit) {
        // Asking inside Claude's own write floor spends a process and an API call to change
        // nothing, so don't.
        cached?.let { snapshot ->
            if (!snapshot.canBeRefreshed(Instant.now())) {
                onDone(snapshot, UsageRefreshResult.AlreadyFresh)
                return
            }
        }
        synchronized(pendingCallbacks) { pendingCallbacks += onDone }
        // Losing this race is fine: every run clears the flag before draining the queue, so
        // the callback queued above is answered either by the in-flight run or by the run
        // this caller would have started.
        if (!refreshing.compareAndSet(false, true)) return
        attemptedAtNanos = System.nanoTime()

        // The flag is cleared inside the coroutine, so if launching itself fails it must be
        // cleared here too — otherwise it stays stuck and every later request is dropped
        // silently, which looks exactly like "clicking does nothing".
        val launched = runCatching {
            scope.launch {
                var result: UsageRefreshResult = UsageRefreshResult.Crashed("did not run")
                var fresh: UsageSnapshot? = null
                try {
                    result = withContext(Dispatchers.IO) { UsageRefresher.refresh() }
                    fresh = withContext(Dispatchers.IO) { reread() }
                } catch (e: CancellationException) {
                    refreshing.set(false)
                    throw e
                } catch (e: Throwable) {
                    thisLogger().warn("Usage refresh failed", e)
                    fresh = cached
                    result = UsageRefreshResult.Crashed(e.message ?: e::class.java.simpleName)
                }
                refreshing.set(false)
                val callbacks = drainCallbacks()
                withContext(Dispatchers.EDT) { callbacks.forEach { it(fresh, result) } }
            }
        }
        if (launched.isFailure) {
            refreshing.set(false)
            thisLogger().warn("Could not start usage refresh", launched.exceptionOrNull())
            val crashed = UsageRefreshResult.Crashed("could not start")
            drainCallbacks().forEach { it(cached, crashed) }
        }
    }

    fun isStale(): Boolean = cached?.isStale(Instant.now()) ?: true

    /**
     * Keeps the figures current on their own, until cancelled.
     *
     * Driven from the tool window via `launchOnShow`, so it stops when the window is not
     * visible. The percentage is only on screen while it is, and a refresh is not free: it
     * starts the whole Claude binary, measured at ~2s wall, ~1s CPU and a ~437MB peak. It
     * consumes no tokens — `/usage` is a local command reading a config endpoint, not a
     * model turn — so the cost is the process, not the quota.
     *
     * The interval comes from settings and is re-read every tick, so changing it — or
     * switching background fetching off — takes effect without restarting the IDE.
     */
    suspend fun autoRefreshLoop() {
        // Let the window settle, and give an explicit click the first go.
        delay(INITIAL_DELAY_MS)
        while (currentCoroutineContext().isActive) {
            refreshIfWorthwhile()
            delay(TICK_MS)
        }
    }

    private suspend fun refreshIfWorthwhile() {
        val settings = service<ClaudeSessionsSettings>()
        if (!settings.usageAutoRefresh) return

        // Whichever is longer: the user's interval, or Claude's floor — inside the floor
        // it would fetch and discard, so there is nothing to gain.
        val due = maxOf(settings.usageRefreshInterval, UsageSnapshot.CLAUDE_WRITE_FLOOR)
        val snapshot = cached ?: withContext(Dispatchers.IO) { reread() }
        if (snapshot != null) {
            if (snapshot.age(Instant.now()) < due) return
        } else if (attemptedAtNanos != 0L && System.nanoTime() - attemptedAtNanos < due.toNanos()) {
            // No snapshot to age-gate on — an API-key account never writes one — so gate on
            // our own attempts. Without this, every tick spawns a full Claude process.
            return
        }
        if (!refreshing.compareAndSet(false, true)) return
        attemptedAtNanos = System.nanoTime()

        var result: UsageRefreshResult = UsageRefreshResult.Crashed("did not run")
        try {
            result = withContext(Dispatchers.IO) { UsageRefresher.refresh() }
            withContext(Dispatchers.IO) { reread() }
        } catch (e: CancellationException) {
            refreshing.set(false)
            throw e
        } catch (e: Throwable) {
            thisLogger().warn("Automatic usage refresh failed", e)
            result = UsageRefreshResult.Crashed(e.message ?: e::class.java.simpleName)
        } finally {
            refreshing.set(false)
        }
        // A popup opened during this run queued its callback here; answer it.
        val callbacks = drainCallbacks()
        if (callbacks.isNotEmpty()) {
            val fresh = cached
            withContext(Dispatchers.EDT) { callbacks.forEach { it(fresh, result) } }
        }
    }

    private companion object {
        const val INITIAL_DELAY_MS = 10_000L

        /** Short, so a settings change is picked up promptly; the interval gates the work. */
        const val TICK_MS = 30_000L
    }
}
