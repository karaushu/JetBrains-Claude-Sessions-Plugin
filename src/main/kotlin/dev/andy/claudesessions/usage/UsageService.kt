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

    @Volatile
    private var cached: UsageSnapshot? = null

    @Volatile
    private var readAtNanos: Long = 0

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
     * Coalesced: a second request while one is in flight is dropped rather than spawning
     * another process. [onDone] runs on the EDT with the newest reading.
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
        if (!refreshing.compareAndSet(false, true)) return

        // The flag is cleared inside the coroutine, so if launching itself fails it must be
        // cleared here too — otherwise it stays stuck and every later request is dropped
        // silently, which looks exactly like "clicking does nothing".
        val launched = runCatching {
            scope.launch {
                var result: UsageRefreshResult = UsageRefreshResult.Crashed("did not run")
                try {
                    result = withContext(Dispatchers.IO) { UsageRefresher.refresh() }
                    val fresh = withContext(Dispatchers.IO) { reread() }
                    withContext(Dispatchers.EDT) { onDone(fresh, result) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    thisLogger().warn("Usage refresh failed", e)
                    val fallback = cached
                    val crashed = UsageRefreshResult.Crashed(e.message ?: e::class.java.simpleName)
                    withContext(Dispatchers.EDT) { onDone(fallback, crashed) }
                } finally {
                    refreshing.set(false)
                }
            }
        }
        if (launched.isFailure) {
            refreshing.set(false)
            thisLogger().warn("Could not start usage refresh", launched.exceptionOrNull())
            onDone(cached, UsageRefreshResult.Crashed("could not start"))
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

        val snapshot = cached ?: withContext(Dispatchers.IO) { reread() }
        if (snapshot != null) {
            val age = snapshot.age(Instant.now())
            // Whichever is longer: the user's interval, or Claude's floor — inside the floor
            // it would fetch and discard, so there is nothing to gain.
            val due = maxOf(settings.usageRefreshInterval, UsageSnapshot.CLAUDE_WRITE_FLOOR)
            if (age < due) return
        }
        if (!refreshing.compareAndSet(false, true)) return

        try {
            withContext(Dispatchers.IO) { UsageRefresher.refresh() }
            withContext(Dispatchers.IO) { reread() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            thisLogger().warn("Automatic usage refresh failed", e)
        } finally {
            refreshing.set(false)
        }
    }

    private companion object {
        const val INITIAL_DELAY_MS = 10_000L

        /** Short, so a settings change is picked up promptly; the interval gates the work. */
        const val TICK_MS = 30_000L
    }
}
