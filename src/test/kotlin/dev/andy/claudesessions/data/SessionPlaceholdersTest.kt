package dev.andy.claudesessions.data

import dev.andy.claudesessions.model.LiveStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionPlaceholdersTest {

    private val project = "/Users/dev/projects/acme"

    private fun live(
        id: String,
        entrypoint: String?,
        cwd: String? = project,
        started: Long? = 1_000L,
        kind: String? = "interactive",
    ) = LiveStatus(1, id, cwd, "idle", null, entrypoint, kind, null, started)

    private fun placeholders(
        vararg statuses: LiveStatus,
        known: Set<String> = emptySet(),
        allProjects: Boolean = false,
    ) = SessionPlaceholders.forUntranscribed(statuses.toList(), known, project, allProjects)

    @Test
    fun `a terminal session with no transcript gets a row`() {
        val rows = placeholders(live("term", "cli"))
        assertEquals(listOf("term"), rows.map { it.sessionId })
        assertFalse(rows.single().hasTranscript)
    }

    @Test
    fun `the IDE's own agent does not get a row`() {
        // WebStorm's Claude panel starts a fresh agent-SDK session on every launch, which
        // added a row on every IDE start for something the user never opened.
        assertTrue(placeholders(live("acp", "sdk-ts")).isEmpty())
        assertTrue(placeholders(live("acp", "sdk-cli")).isEmpty())
    }

    @Test
    fun `daemon background jobs do not get a row, even though they report cli`() {
        // claude bg-spare and background agents are spawned by the daemon and report
        // entrypoint = cli exactly like a terminal session; only `kind` separates them.
        assertTrue(placeholders(live("spare", "cli", kind = "bg")).isEmpty())
        assertTrue(placeholders(live("worker", "cli", kind = "daemon-worker")).isEmpty())
        assertTrue(placeholders(live("daemon", "cli", kind = "daemon")).isEmpty())
    }

    @Test
    fun `a missing kind is not assumed to be interactive`() {
        assertTrue(placeholders(live("unknown", "cli", kind = null)).isEmpty())
    }

    @Test
    fun `Claude Desktop does not get a row either`() {
        assertTrue(placeholders(live("desk", "claude-desktop")).isEmpty())
    }

    @Test
    fun `an unknown entrypoint is not assumed to be a terminal`() {
        assertTrue(placeholders(live("odd", "something-new")).isEmpty())
        assertTrue(placeholders(live("none", null)).isEmpty())
    }

    @Test
    fun `a session that already has a transcript is left to the normal scan`() {
        assertTrue(placeholders(live("term", "cli"), known = setOf("term")).isEmpty())
    }

    @Test
    fun `sessions from other projects are excluded unless showing all`() {
        val elsewhere = live("other", "cli", cwd = "/somewhere/else")
        assertTrue(placeholders(elsewhere).isEmpty())
        assertEquals(listOf("other"), placeholders(elsewhere, allProjects = true).map { it.sessionId })
    }

    @Test
    fun `last activity comes from the start time, so the row does not churn each poll`() {
        val row = placeholders(live("term", "cli", started = 5_000L)).single()
        assertEquals(5_000L, row.startedAt?.toEpochMilli())
        assertEquals(5_000L, row.lastActivity.toEpochMilli())
    }

    @Test
    fun `the expected transcript path is where the session will actually write`() {
        val row = placeholders(live("term", "cli")).single()
        assertTrue(row.transcript.toString().endsWith("-Users-dev-projects-acme/term.jsonl"), row.transcript.toString())
    }
}
