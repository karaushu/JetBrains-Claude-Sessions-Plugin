package dev.andy.claudesessions.terminal

import dev.andy.claudesessions.model.LiveStatus
import dev.andy.claudesessions.model.SessionItem
import dev.andy.claudesessions.model.SessionSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class SessionAdoptionTest {

    private val cwd = "/Users/dev/projects/web/admin"
    private val launchedAt = Instant.parse("2026-07-27T19:00:00Z").toEpochMilli()

    private fun session(
        id: String,
        startedAt: Instant?,
        sessionCwd: String? = cwd,
        live: Boolean = false,
        entrypoint: String = "cli",
        transcript: Path = Path.of("/tmp/$id.jsonl"),
    ) = SessionItem(
        summary = SessionSummary(
            sessionId = id,
            transcript = transcript,
            cwd = sessionCwd,
            gitBranch = "staging",
            title = null,
            startedAt = startedAt,
            lastActivity = Instant.parse("2026-07-27T19:05:00Z"),
            sizeBytes = 10,
        ),
        live = if (live) LiveStatus(1, id, sessionCwd, "idle", null, entrypoint, "interactive", null, null) else null,
    )

    private fun pick(items: List<SessionItem>, claimed: Set<String> = emptySet()) =
        SessionAdoption.pick(items, cwd, launchedAt, claimed)

    @Test
    fun `adopts a session started just after the launch`() {
        val match = pick(listOf(session("new", Instant.parse("2026-07-27T19:00:03Z"))))
        assertEquals("new", match?.sessionId)
    }

    @Test
    fun `does not need a pid file`() {
        // The old rule required a live entry whose entrypoint was "cli", which is why tabs
        // routinely went unadopted: not every session has a status sidecar.
        val match = pick(listOf(session("no-pid", Instant.parse("2026-07-27T19:00:02Z"), live = false)))
        assertEquals("no-pid", match?.sessionId)
    }

    @Test
    fun `does not care what the entrypoint says`() {
        val match = pick(
            listOf(session("desktop", Instant.parse("2026-07-27T19:00:02Z"), live = true, entrypoint = "claude-desktop")),
        )
        assertEquals("desktop", match?.sessionId)
    }

    @Test
    fun `ignores sessions that predate the launch`() {
        assertNull(pick(listOf(session("old", Instant.parse("2026-07-27T18:30:00Z")))))
    }

    @Test
    fun `tolerates a start time slightly before ours, since the CLI stamps its own`() {
        val justBefore = Instant.ofEpochMilli(launchedAt - 2_000)
        assertEquals("ok", pick(listOf(session("ok", justBefore)))?.sessionId)

        val wellBefore = Instant.ofEpochMilli(launchedAt - SessionAdoption.CLOCK_SLACK_MILLIS - 1_000)
        assertNull(pick(listOf(session("too-early", wellBefore))))
    }

    @Test
    fun `ignores sessions from another directory`() {
        assertNull(
            pick(listOf(session("elsewhere", Instant.parse("2026-07-27T19:00:03Z"), sessionCwd = "/other/repo"))),
        )
    }

    @Test
    fun `accepts a no-cwd session whose transcript sits in the launch directory's folder`() {
        val match = pick(
            listOf(
                session(
                    "nocwd",
                    Instant.parse("2026-07-27T19:00:03Z"),
                    sessionCwd = null,
                    transcript = Path.of("/claude/projects/-Users-dev-projects-web-admin/nocwd.jsonl"),
                ),
            ),
        )
        assertEquals("nocwd", match?.sessionId)
    }

    @Test
    fun `rejects a no-cwd session from another project's folder`() {
        // With "show all projects" on the candidate list spans every project, so "we found
        // it while scanning" no longer implies "it is ours".
        assertNull(
            pick(
                listOf(
                    session(
                        "foreign",
                        Instant.parse("2026-07-27T19:00:03Z"),
                        sessionCwd = null,
                        transcript = Path.of("/claude/projects/-other-repo/foreign.jsonl"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `never steals a session another tab already owns`() {
        val items = listOf(
            session("taken", Instant.parse("2026-07-27T19:00:01Z")),
            session("free", Instant.parse("2026-07-27T19:00:04Z")),
        )
        assertEquals("free", pick(items, claimed = setOf("taken"))?.sessionId)
    }

    @Test
    fun `picks the earliest qualifying session when two start close together`() {
        val items = listOf(
            session("later", Instant.parse("2026-07-27T19:00:09Z")),
            session("earlier", Instant.parse("2026-07-27T19:00:02Z")),
        )
        assertEquals("earlier", pick(items)?.sessionId)
    }

    @Test
    fun `a session with no start time is not guessed at`() {
        assertNull(pick(listOf(session("unknown", null))))
    }

    @Test
    fun `no candidates yields nothing rather than an arbitrary pick`() {
        assertNull(pick(emptyList()))
    }
}
