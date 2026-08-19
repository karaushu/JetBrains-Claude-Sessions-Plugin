package dev.andy.claudesessions.terminal

import dev.andy.claudesessions.data.ClaudePaths
import dev.andy.claudesessions.model.SessionItem
import java.time.Instant

/**
 * Works out which session a `+` tab actually started.
 *
 * There is no handle joining the two — Claude assigns the id itself — so the match is on
 * what the session recorded: it lives in the directory we launched in, and it began no
 * earlier than we launched.
 *
 * Deliberately based on the transcript rather than `~/.claude/sessions/<pid>.json`. An
 * earlier version required a pid file whose `entrypoint` was `cli`, which made adoption
 * depend on the status sidecar existing and on entrypoint detection agreeing — so a tab
 * routinely stayed unadopted, kept its placeholder title, and reported itself as running
 * elsewhere when clicked. Every session has a transcript; not every session has a pid file.
 */
internal object SessionAdoption {

    /** The CLI stamps its own start time, which can slightly precede our launch call. */
    const val CLOCK_SLACK_MILLIS = 5_000L

    fun pick(
        items: List<SessionItem>,
        workingDirectory: String?,
        launchedAtMillis: Long,
        claimed: Set<String>,
    ): SessionItem? = items
        .asSequence()
        .filter { it.sessionId !in claimed }
        .filter { workingDirectory == null || matchesLaunchDirectory(it, workingDirectory) }
        .filter { startedAfterLaunch(it, launchedAtMillis) }
        // Earliest qualifying session: if two were started close together, ours came first.
        .minByOrNull { it.summary.startedAt ?: Instant.MAX }

    /**
     * A candidate with no recorded cwd is only trusted when its transcript sits in the
     * launch directory's own transcript folder. "It was found in the directory we scanned"
     * used to be enough — but with "show all projects" on, [pick] sees every project's
     * sessions, and an unrelated null-cwd session could be adopted by this tab.
     */
    private fun matchesLaunchDirectory(item: SessionItem, workingDirectory: String): Boolean {
        item.summary.cwd?.let { return it == workingDirectory }
        return item.summary.transcript.parent?.fileName?.toString() ==
            ClaudePaths.encodeProjectDir(workingDirectory)
    }

    private fun startedAfterLaunch(item: SessionItem, launchedAtMillis: Long): Boolean {
        val started = item.summary.startedAt ?: return false
        return started.toEpochMilli() >= launchedAtMillis - CLOCK_SLACK_MILLIS
    }
}
