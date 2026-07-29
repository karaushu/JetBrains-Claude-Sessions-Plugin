package dev.andy.claudesessions.hooks

import dev.andy.claudesessions.model.SessionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.writeText

class HookEventLogTest {

    private fun line(sessionId: String, event: String, notificationType: String? = null): String {
        val type = notificationType?.let { ""","notification_type":"$it"""" } ?: ""
        return """{"session_id":"$sessionId","transcript_path":"/t.jsonl","cwd":"/repo","hook_event_name":"$event"$type}"""
    }

    @Test
    fun `reads nothing when the log does not exist`(@TempDir dir: Path) {
        assertTrue(HookEventLog(dir.resolve("absent.jsonl")).readNew().isEmpty())
    }

    @Test
    fun `returns only lines appended since the last read`(@TempDir dir: Path) {
        val file = dir.resolve("events.jsonl")
        file.writeText(line("a", "UserPromptSubmit") + "\n")
        val log = HookEventLog(file)

        assertEquals(listOf("a"), log.readNew().map { it.sessionId })
        // Nothing new.
        assertTrue(log.readNew().isEmpty())

        file.appendText(line("b", "Stop") + "\n")
        assertEquals(listOf("b"), log.readNew().map { it.sessionId })
    }

    @Test
    fun `parses the snake_case fields Claude actually sends`(@TempDir dir: Path) {
        val file = dir.resolve("events.jsonl")
        file.writeText(line("sess-1", "Notification", "permission_prompt") + "\n")

        val event = HookEventLog(file).readNew().single()
        assertEquals("sess-1", event.sessionId)
        assertEquals("Notification", event.eventName)
        assertEquals("permission_prompt", event.notificationType)
        assertEquals("/repo", event.cwd)
    }

    @Test
    fun `skips torn and malformed lines instead of failing the batch`(@TempDir dir: Path) {
        // A long payload could interleave with a concurrent append; a torn line must not
        // cost us the rest of the batch.
        val file = dir.resolve("events.jsonl")
        file.writeText(
            buildString {
                appendLine(line("a", "Stop"))
                appendLine("""{"session_id":"torn","hook_event_""")
                appendLine("not json at all")
                appendLine("""{"hook_event_name":"Stop"}""") // no session_id
                appendLine(line("b", "Stop"))
            },
        )

        assertEquals(listOf("a", "b"), HookEventLog(file).readNew().map { it.sessionId })
    }

    @Test
    fun `starts over when the log is truncated behind our back`(@TempDir dir: Path) {
        val file = dir.resolve("events.jsonl")
        file.writeText(line("a", "Stop") + "\n" + line("b", "Stop") + "\n")
        val log = HookEventLog(file)
        assertEquals(2, log.readNew().size)

        // Rotated: smaller than the offset we had.
        file.writeText(line("c", "Stop") + "\n")
        assertEquals(listOf("c"), log.readNew().map { it.sessionId })
    }
}

class HookStatusTrackerTest {

    private fun line(sessionId: String, event: String, notificationType: String? = null): String {
        val type = notificationType?.let { ""","notification_type":"$it"""" } ?: ""
        return """{"session_id":"$sessionId","transcript_path":"/t.jsonl","cwd":"/repo","hook_event_name":"$event"$type}"""
    }

    @Test
    fun `last event wins per session`(@TempDir dir: Path) {
        val file = dir.resolve("events.jsonl")
        file.writeText(
            buildString {
                appendLine(line("a", "UserPromptSubmit"))
                appendLine(line("a", "Notification", "permission_prompt"))
                appendLine(line("b", "UserPromptSubmit"))
            },
        )
        val log = HookEventLog(file)
        val tracker = HookStatusTracker()

        assertTrue(tracker.apply(log.readNew()))
        assertEquals(SessionState.NEEDS_INPUT, tracker.states()["a"])
        assertEquals(SessionState.RUNNING, tracker.states()["b"])
    }

    @Test
    fun `an ended session is dropped rather than left looking alive`(@TempDir dir: Path) {
        val file = dir.resolve("events.jsonl")
        file.writeText(line("a", "UserPromptSubmit") + "\n")
        val log = HookEventLog(file)
        val tracker = HookStatusTracker()
        tracker.apply(log.readNew())
        assertEquals(SessionState.RUNNING, tracker.states()["a"])

        file.appendText(line("a", "SessionEnd") + "\n")
        assertTrue(tracker.apply(log.readNew()))
        assertFalse("a" in tracker.states())
    }

    @Test
    fun `poll reports no change when nothing was appended`(@TempDir dir: Path) {
        val file = dir.resolve("events.jsonl")
        file.writeText(line("a", "Stop") + "\n")
        val log = HookEventLog(file)
        val tracker = HookStatusTracker()

        assertTrue(tracker.apply(log.readNew()))
        // This is the common case, and it must be cheap and quiet.
        assertFalse(tracker.apply(log.readNew()))
    }

    @Test
    fun `events that say nothing about state do not report a change`(@TempDir dir: Path) {
        val file = dir.resolve("events.jsonl")
        file.writeText(line("a", "Notification", "auth_success") + "\n")
        val log = HookEventLog(file)
        val tracker = HookStatusTracker()

        assertFalse(tracker.apply(log.readNew()))
        assertTrue(tracker.states().isEmpty())
    }
}
