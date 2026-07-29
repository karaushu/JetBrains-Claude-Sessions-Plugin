package dev.andy.claudesessions.data

import dev.andy.claudesessions.model.LiveStatus
import dev.andy.claudesessions.model.SessionSummary
import java.time.Instant

/**
 * Rows for sessions that are running but have not written a transcript yet.
 *
 * A fresh `claude` writes nothing to `~/.claude/projects` until its first message, so a
 * session started seconds ago is real and yet absent from every transcript-derived list.
 * Without these a session opened with `+` would not appear at all, and there would be
 * nothing for [dev.andy.claudesessions.terminal.SessionAdoption] to bind its tab to.
 *
 * Only interactive terminal sessions qualify. The IDE's own Claude panel starts an agent-SDK
 * session on every launch and Claude Desktop starts its own; separately, the daemon spawns
 * background jobs that report `entrypoint = cli` and are distinguished only by `kind`. All of
 * those are infrastructure rather than something the user opened from this list. Once such a
 * session writes a transcript it appears through the normal scan, because by then it is a
 * real conversation.
 */
internal object SessionPlaceholders {

    /** The only entrypoint a human drives from a terminal, and the one `+` produces. */
    const val TERMINAL_ENTRYPOINT = "cli"

    /**
     * Background jobs the daemon spawns — `claude bg-spare`, background agents — also report
     * `entrypoint = cli`, so the entrypoint alone is not enough. Only an interactive session
     * is one a person is sitting in front of.
     */
    const val INTERACTIVE_KIND = "interactive"

    fun forUntranscribed(
        live: Collection<LiveStatus>,
        knownSessionIds: Set<String>,
        projectBasePath: String?,
        allProjects: Boolean,
    ): List<SessionSummary> = live
        .filter { it.sessionId !in knownSessionIds }
        .filter { it.entrypoint == TERMINAL_ENTRYPOINT }
        .filter { it.kind == INTERACTIVE_KIND }
        .filter { allProjects || (it.cwd != null && it.cwd == projectBasePath) }
        .map(::placeholder)

    private fun placeholder(status: LiveStatus): SessionSummary {
        // Start time doubles as last activity: using "now" would make the row differ on
        // every poll and churn the tree.
        val started = status.startedAtMillis?.let(Instant::ofEpochMilli)
        return SessionSummary(
            sessionId = status.sessionId,
            transcript = expectedTranscript(status),
            cwd = status.cwd,
            gitBranch = null,
            title = null,
            startedAt = started,
            lastActivity = started ?: Instant.now(),
            sizeBytes = 0,
            hasTranscript = false,
        )
    }

    /** Where the transcript will land once the session writes one. */
    private fun expectedTranscript(status: LiveStatus) =
        (status.cwd?.let { ClaudePaths.projects.resolve(ClaudePaths.encodeProjectDir(it)) } ?: ClaudePaths.projects)
            .resolve("${status.sessionId}.jsonl")
}
