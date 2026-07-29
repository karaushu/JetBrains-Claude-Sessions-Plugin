package dev.andy.claudesessions.usage

import java.time.Duration
import java.time.Instant

/**
 * One usage window, as the server reports it.
 *
 * These come straight from `cachedUsageUtilization.utilization.limits[]` in `~/.claude.json`,
 * which is the same array the desktop app renders — no API call and no credentials.
 */
internal data class UsageLimit(
    /** `session`, `weekly_all`, `weekly_scoped`, or something added later. */
    val kind: String,
    val percent: Int,
    val resetsAt: Instant?,
    /** Model name for a scoped window, e.g. `Fable`. */
    val scopeModel: String?,
) {
    val label: String
        get() = when (kind) {
            "session" -> "5-hour limit"
            "weekly_all" -> "Weekly · all models"
            "weekly_scoped" -> "Weekly · ${scopeModel ?: "scoped"}"
            // The server can add kinds; show something rather than dropping the row.
            else -> kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }

    /** A window whose reset time has passed has already rolled over; its percent is meaningless. */
    fun hasRolledOver(now: Instant): Boolean = resetsAt != null && resetsAt.isBefore(now)
}

/**
 * A read of the cached usage data, with enough context to judge whether to trust it.
 */
internal data class UsageSnapshot(
    val limits: List<UsageLimit>,
    val fetchedAt: Instant,
) {
    fun age(now: Instant): Duration = Duration.between(fetchedAt, now)

    /**
     * Claude Code discards its own persisted cache after an hour, so we use the same bound.
     * Nothing refreshes it while Claude is not running, which is often.
     */
    fun isStale(now: Instant): Boolean = age(now) > MAX_TRUSTED_AGE

    /** The window most worth putting in the toolbar. */
    fun sessionLimit(): UsageLimit? = limits.firstOrNull { it.kind == "session" }

    /**
     * Whether asking Claude would achieve anything.
     *
     * Claude will not rewrite its cache while the existing entry is younger than
     * [CLAUDE_WRITE_FLOOR] — it fetches and then discards the result. Running `/usage`
     * inside that window costs a process and an API round trip and changes nothing, so a
     * reading a few minutes old is as fresh as it is possible to get.
     */
    fun canBeRefreshed(now: Instant): Boolean = age(now) >= CLAUDE_WRITE_FLOOR

    companion object {
        val MAX_TRUSTED_AGE: Duration = Duration.ofHours(1)

        /** Mirrors the CLI's own throttle on rewriting cachedUsageUtilization. */
        val CLAUDE_WRITE_FLOOR: Duration = Duration.ofMinutes(5)
    }
}
