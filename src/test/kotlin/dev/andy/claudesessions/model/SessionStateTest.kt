package dev.andy.claudesessions.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessionStateTest {

    private fun status(value: String?) = LiveStatus(
        pid = 1,
        sessionId = "s",
        cwd = null,
        status = value,
        waitingFor = null,
        entrypoint = "cli",
        kind = "interactive",
        name = null,
        startedAtMillis = null,
    )

    @Test
    fun `maps the four statuses the CLI actually writes`() {
        assertEquals(SessionState.RUNNING, status("busy").state)
        assertEquals(SessionState.NEEDS_INPUT, status("waiting").state)
        assertEquals(SessionState.LIVE_IDLE, status("idle").state)
        // `shell` means idle at the prompt with background jobs still running.
        assertEquals(SessionState.LIVE_IDLE, status("shell").state)
    }

    @Test
    fun `absent status is not guessed at`() {
        // Only the interactive TUI writes status; desktop and SDK sessions omit it.
        assertEquals(SessionState.RUNNING_UNKNOWN, status(null).state)
        assertEquals(SessionState.RUNNING_UNKNOWN, status("something-new").state)
    }

    private fun summary() = SessionSummary(
        sessionId = "s",
        transcript = java.nio.file.Path.of("/tmp/s.jsonl"),
        cwd = null,
        gitBranch = null,
        title = null,
        startedAt = null,
        lastActivity = java.time.Instant.EPOCH,
        sizeBytes = 0,
    )

    private fun item(live: LiveStatus?, hookState: SessionState? = null) =
        SessionItem(summary = summary(), live = live, hookState = hookState)

    @Test
    fun `a session with no live entry is historical and needs no attention`() {
        val item = item(live = null)

        assertEquals(SessionState.HISTORICAL, item.state)
        assertEquals(false, item.isLive)
        assertEquals(false, item.needsAttention)
    }

    @Test
    fun `hook state does not resurrect a session whose process is gone`() {
        // A session interrupted mid-turn leaves a UserPromptSubmit with no Stop after it, and
        // hooks have no event for "killed". Trusting that left a dead session spinning at the
        // top of the list until the event log next rotated.
        val item = item(live = null, hookState = SessionState.RUNNING)

        assertEquals(SessionState.HISTORICAL, item.state)
        assertEquals(false, item.isLive)
        assertEquals(false, item.needsAttention)
    }

    @Test
    fun `a stale hook state cannot make a dead session look like it wants input either`() {
        val item = item(live = null, hookState = SessionState.NEEDS_INPUT)

        assertEquals(SessionState.HISTORICAL, item.state)
        assertEquals(false, item.needsAttention)
    }

    @Test
    fun `the pid file decides liveness for every entrypoint`() {
        // Desktop and SDK sessions write a pid file too; only `status` is the TUI's alone.
        assertEquals(true, item(live = status(null)).isLive)
        assertEquals(true, item(live = status("busy")).isLive)
    }

    @Test
    fun `hooks supply the state a non-TUI session cannot report itself`() {
        assertEquals(SessionState.RUNNING, item(status(null), SessionState.RUNNING).state)
        assertEquals(SessionState.NEEDS_INPUT, item(status(null), SessionState.NEEDS_INPUT).state)
        assertEquals(true, item(status(null), SessionState.NEEDS_INPUT).needsAttention)
        assertEquals(SessionState.LIVE_IDLE, item(status(null), SessionState.LIVE_IDLE).state)
    }

    @Test
    fun `a live session is never shown as historical on a hook's word`() {
        // The tracker drops ended sessions rather than storing HISTORICAL, but a live process
        // must not be written off even if one arrived.
        assertEquals(
            SessionState.RUNNING_UNKNOWN,
            item(status(null), SessionState.HISTORICAL).state,
        )
        assertEquals(true, item(status(null), SessionState.HISTORICAL).isLive)
    }

    @Test
    fun `the pid file's own status wins over hooks`() {
        // The TUI writes status on every change, and it carries waitingFor with it.
        assertEquals(SessionState.LIVE_IDLE, item(status("idle"), SessionState.RUNNING).state)
        assertEquals(SessionState.RUNNING, item(status("busy"), SessionState.LIVE_IDLE).state)
    }
}
