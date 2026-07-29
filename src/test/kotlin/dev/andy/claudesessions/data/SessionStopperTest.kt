package dev.andy.claudesessions.data

import dev.andy.claudesessions.model.LiveStatus
import dev.andy.claudesessions.model.SessionItem
import dev.andy.claudesessions.model.SessionSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class SessionStopperTest {

    private fun item(live: Boolean) = SessionItem(
        summary = SessionSummary(
            sessionId = "s", transcript = Path.of("/tmp/s.jsonl"), cwd = "/repo",
            gitBranch = null, title = null, startedAt = null,
            lastActivity = Instant.EPOCH, sizeBytes = 0,
        ),
        live = if (live) LiveStatus(1, "s", "/repo", "idle", null, "cli", "interactive", null, null) else null,
    )

    @Test
    fun `only a session with a live process can be stopped`() {
        assertTrue(SessionStopper.canStop(item(live = true)))
        assertFalse(SessionStopper.canStop(item(live = false)))
    }

    @Test
    fun `stopping a pid that no longer exists is reported, not treated as success`() {
        // A pid file outlives an unclean exit, so this is a normal case rather than an error.
        val result = SessionStopper.stop(pid = 999_999_999L)
        assertEquals(SessionStopper.Result.AlreadyGone, result)
    }

    @Test
    fun `a recycled pid is refused rather than killed`() {
        // The important safety property: a stale pid file must never make us signal an
        // unrelated process that happens to have inherited the number.
        val process = ProcessBuilder("sleep", "30").start()
        try {
            val result = SessionStopper.stop(process.pid(), requireCommandContaining = "claude")
            assertEquals(SessionStopper.Result.NotClaude, result)
            assertTrue(process.isAlive, "the unrelated process must be left running")
        } finally {
            process.destroyForcibly()
            process.waitFor()
        }
    }

    @Test
    fun `a matching process is actually terminated`() {
        // Exercises the real signalling path against a process we own, with the identity
        // check pointed at it instead of Claude.
        val process = ProcessBuilder("sleep", "30").start()
        try {
            val result = SessionStopper.stop(process.pid(), requireCommandContaining = "sleep")
            assertEquals(SessionStopper.Result.Stopped, result)
            assertFalse(process.isAlive, "process should have exited")
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor()
            }
        }
    }

    @Test
    fun `a process that ignores SIGTERM is escalated`() {
        // 'trap' makes the shell ignore SIGTERM; only destroyForcibly can end it.
        val process = ProcessBuilder("/bin/sh", "-c", "trap '' TERM; sleep 30").start()
        try {
            val result = SessionStopper.stop(
                process.pid(),
                requireCommandContaining = "sh",
                graceMillis = 1_000L,
            )
            assertInstanceOf(SessionStopper.Result.Stopped::class.java, result)
            assertFalse(process.isAlive)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor()
            }
        }
    }
}
