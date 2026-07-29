package dev.andy.claudesessions.data

import dev.andy.claudesessions.model.SessionItem
import java.util.concurrent.TimeUnit

/**
 * Terminates a session's process.
 *
 * The pid comes from `~/.claude/sessions/<pid>.json`, which is written by the CLI and only
 * best-effort cleaned up, so it can name a pid that has since been recycled by something
 * else entirely. Every stop therefore re-checks that the process still looks like Claude
 * before signalling it — killing an arbitrary pid because a stale file pointed at it would
 * be a genuinely bad outcome.
 */
internal object SessionStopper {

    sealed interface Result {
        object Stopped : Result

        /** It had already exited; nothing to do. */
        object AlreadyGone : Result

        /** The pid is alive but is not Claude — almost certainly recycled. */
        object NotClaude : Result

        data class Failed(val message: String) : Result
    }

    /** How long to wait for SIGKILL to take effect; it is delivered asynchronously. */
    private const val FORCE_GRACE_MILLIS = 3_000L

    fun canStop(item: SessionItem): Boolean = item.live != null

    /** True if the process exited within the window. */
    private fun awaitExit(handle: ProcessHandle, millis: Long): Boolean {
        runCatching {
            handle.onExit().orTimeout(millis, TimeUnit.MILLISECONDS).get()
        }
        return !handle.isAlive
    }

    /**
     * Signals the process and waits briefly for it to go.
     *
     * Blocking, so call it off the EDT. SIGTERM first, because Claude removes its own pid
     * file and flushes its transcript on a clean exit; only escalates if it ignores that.
     *
     * [requireCommandContaining] is injectable so this can be tested against a process that
     * is not Claude.
     */
    fun stop(
        pid: Long,
        requireCommandContaining: String = "claude",
        graceMillis: Long = 4_000L,
    ): Result {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return Result.AlreadyGone
        if (!handle.isAlive) return Result.AlreadyGone

        val command = handle.info().command().orElse(null)
        if (command != null && !command.contains(requireCommandContaining, ignoreCase = true)) {
            return Result.NotClaude
        }

        return runCatching {
            handle.destroy()
            if (!awaitExit(handle, graceMillis)) {
                // SIGKILL is asynchronous too, so it needs its own wait before the process
                // can be declared unkillable.
                handle.destroyForcibly()
                awaitExit(handle, FORCE_GRACE_MILLIS)
            }
            if (handle.isAlive) Result.Failed("process $pid ignored both signals") else Result.Stopped
        }.getOrElse { Result.Failed(it.message ?: it::class.java.simpleName) }
    }
}
