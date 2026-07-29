package dev.andy.claudesessions.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class UntitledDescriptionTest {

    private fun item(
        hasTranscript: Boolean,
        entrypoint: String? = null,
        live: Boolean = true,
        title: String? = null,
    ) = SessionItem(
        summary = SessionSummary(
            sessionId = "abcdef12-0000-0000-0000-000000000000",
            transcript = Path.of("/tmp/x.jsonl"),
            cwd = "/repo",
            gitBranch = null,
            title = title,
            startedAt = Instant.EPOCH,
            lastActivity = Instant.EPOCH,
            sizeBytes = 0,
            hasTranscript = hasTranscript,
        ),
        live = if (live) {
            LiveStatus(1, "abcdef12", "/repo", "idle", null, entrypoint, "interactive", null, null)
        } else {
            null
        },
    )

    @Test
    fun `a background agent is recognised as one`() {
        val bg = SessionItem(
            summary = SessionSummary(
                sessionId = "bg", transcript = Path.of("/tmp/bg.jsonl"), cwd = "/repo",
                gitBranch = null, title = "Some task", startedAt = Instant.EPOCH,
                lastActivity = Instant.EPOCH, sizeBytes = 1,
            ),
            live = LiveStatus(1, "bg", "/repo", "idle", null, "cli", "bg", null, null),
        )
        assertEquals(true, bg.isBackgroundAgent)
    }

    @Test
    fun `an interactive session is not a background agent`() {
        assertEquals(false, item(hasTranscript = true, entrypoint = "cli").isBackgroundAgent)
    }

    @Test
    fun `a terminal session with nothing sent says so`() {
        assertEquals(
            "New session — nothing sent yet",
            item(hasTranscript = false, entrypoint = "cli").untitledDescription,
        )
    }

    @Test
    fun `an IDE agent is not described as a new terminal session`() {
        // This is the case that mattered: the WebStorm Claude panel runs an agent-SDK
        // session with no transcript. Calling it a new session made a process in active
        // use look abandoned.
        assertEquals(
            "IDE agent — not started from this list",
            item(hasTranscript = false, entrypoint = "sdk-ts").untitledDescription,
        )
        assertEquals(
            "IDE agent — not started from this list",
            item(hasTranscript = false, entrypoint = "sdk-cli").untitledDescription,
        )
    }

    @Test
    fun `the desktop app is named as such`() {
        assertEquals(
            "Claude Desktop session",
            item(hasTranscript = false, entrypoint = "claude-desktop").untitledDescription,
        )
    }

    @Test
    fun `an unknown entrypoint is not guessed at`() {
        assertEquals("Untitled session", item(hasTranscript = false, entrypoint = "something-new").untitledDescription)
        assertEquals("Untitled session", item(hasTranscript = false, entrypoint = null).untitledDescription)
    }

    @Test
    fun `a session with a transcript but no title is simply untitled`() {
        assertEquals("Untitled session", item(hasTranscript = true, entrypoint = "cli").untitledDescription)
    }

    @Test
    fun `a dead session is never called new`() {
        assertEquals("Untitled session", item(hasTranscript = false, live = false).untitledDescription)
    }
}
