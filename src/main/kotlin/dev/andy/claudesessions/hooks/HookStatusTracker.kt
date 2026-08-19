package dev.andy.claudesessions.hooks

import dev.andy.claudesessions.model.SessionState

/**
 * Folds a hook event stream into a per-session state.
 *
 * Events arrive in append order, so the last one that says something about a session wins.
 * This is the only status source that covers non-TUI entrypoints.
 *
 * Reading the log is deliberately not this class's job: [HookEventBus] owns the single tail
 * so that state folding and notifications can consume the same batch. Two independent
 * readers would fight over truncation and replay each other's events.
 */
internal class HookStatusTracker {

    // Written by the bus's tail coroutine; read via states() from store refreshes, action
    // updates and the review watcher, which run on other threads — hence the lock.
    private val bySessionId = mutableMapOf<String, HookDerivedState>()

    /**
     * Applies a batch and returns true if anything changed, so the caller can treat a hook
     * event as a nudge to re-read the authoritative status files at once instead of waiting
     * for the next scheduled poll.
     */
    fun apply(events: List<HookEvent>): Boolean {
        var changed = false
        synchronized(bySessionId) {
            for (event in events) {
                val state = event.derivedState() ?: continue
                if (state == HookDerivedState.ENDED) {
                    changed = bySessionId.remove(event.sessionId) != null || changed
                } else if (bySessionId.put(event.sessionId, state) != state) {
                    changed = true
                }
            }
        }
        return changed
    }

    /** State for sessions the hooks have seen, for use where no pid file exists. */
    fun states(): Map<String, SessionState> = synchronized(bySessionId) {
        bySessionId.mapValues { (_, state) -> state.toSessionState() }
    }

    /** Drops every session not in [sessionIds]; true when anything was removed. */
    fun retainAll(sessionIds: Set<String>): Boolean = synchronized(bySessionId) {
        bySessionId.keys.retainAll(sessionIds)
    }
}
