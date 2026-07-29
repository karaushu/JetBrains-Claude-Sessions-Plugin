package dev.andy.claudesessions.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.andy.claudesessions.usage.UsageSnapshot
import java.time.Duration

/**
 * Persisted plugin settings.
 *
 * Application-level rather than per-project: usage limits are an account-wide thing, and
 * having one project fetch on a different schedule from another would be surprising.
 */
@Service(Service.Level.APP)
@State(name = "ClaudeSessionsSettings", storages = [Storage("claudeSessions.xml")])
internal class ClaudeSessionsSettings : PersistentStateComponent<ClaudeSessionsSettings.State> {

    class State {
        /** Whether to fetch usage figures in the background at all. */
        var usageAutoRefresh: Boolean = true

        /** How old a reading may get before it is refreshed. */
        var usageRefreshMinutes: Int = DEFAULT_REFRESH_MINUTES

        /** Announce that Claude finished a turn, or that a background agent completed. */
        var notifyOnTurnEnd: Boolean = true

        /** Announce a permission prompt, a plan approval, or any other blocking ask. */
        var notifyOnPrompt: Boolean = true
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    var usageAutoRefresh: Boolean
        get() = state.usageAutoRefresh
        set(value) {
            state.usageAutoRefresh = value
        }

    /**
     * Clamped on read as well as in the UI, so a hand-edited config cannot produce a value
     * that hammers Claude or effectively disables refreshing without saying so.
     */
    var usageRefreshMinutes: Int
        get() = state.usageRefreshMinutes.coerceIn(MIN_REFRESH_MINUTES, MAX_REFRESH_MINUTES)
        set(value) {
            state.usageRefreshMinutes = value.coerceIn(MIN_REFRESH_MINUTES, MAX_REFRESH_MINUTES)
        }

    val usageRefreshInterval: Duration get() = Duration.ofMinutes(usageRefreshMinutes.toLong())

    var notifyOnTurnEnd: Boolean
        get() = state.notifyOnTurnEnd
        set(value) {
            state.notifyOnTurnEnd = value
        }

    var notifyOnPrompt: Boolean
        get() = state.notifyOnPrompt
        set(value) {
            state.notifyOnPrompt = value
        }

    /** Whether anything at all wants the hook stream watched. */
    val notifiesAnything: Boolean get() = notifyOnTurnEnd || notifyOnPrompt

    companion object {
        const val DEFAULT_REFRESH_MINUTES = 10

        /**
         * Claude refuses to rewrite its usage cache more often than every five minutes, so
         * anything below that would spend a process to change nothing.
         */
        val MIN_REFRESH_MINUTES: Int = UsageSnapshot.CLAUDE_WRITE_FLOOR.toMinutes().toInt()

        const val MAX_REFRESH_MINUTES = 240
    }
}
