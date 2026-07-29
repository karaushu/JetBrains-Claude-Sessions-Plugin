package dev.andy.claudesessions.hooks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.writeText

/**
 * Parsing of the fields notifications are built from.
 *
 * The lines are real ones, copied out of `~/.claude/claudesessions/events.jsonl` and shortened:
 * the payload carries considerably more than Claude's hook documentation lists, and guessing
 * at it would have meant reading transcripts to recover things already in hand.
 */
class HookEventPayloadTest {

    @Test
    fun `a Stop payload yields the closing message and the stop-hook flag`(@TempDir dir: Path) {
        val file = dir.resolve("events.jsonl")
        file.writeText(
            """{"session_id":"6895353a","transcript_path":"/Users/dev/.claude/projects/-repo/6895353a.jsonl",""" +
                """"cwd":"/Users/dev/projects/acme","prompt_id":"362866c9","permission_mode":"auto",""" +
                """"effort":{"level":"medium"},"hook_event_name":"Stop","stop_hook_active":false,""" +
                """"last_assistant_message":"Both pushed.","background_tasks":[],"session_crons":[]}""" + "\n",
        )

        val event = HookEventLog(file).readNew().single()
        assertEquals("Stop", event.eventName)
        assertEquals("Both pushed.", event.lastAssistantMessage)
        assertFalse(event.stopHookActive)
        assertEquals("/Users/dev/.claude/projects/-repo/6895353a.jsonl", event.transcriptPath)
    }

    @Test
    fun `stop_hook_active true is read as true`(@TempDir dir: Path) {
        val file = dir.resolve("events.jsonl")
        file.writeText(
            """{"session_id":"a","hook_event_name":"Stop","stop_hook_active":true}""" + "\n",
        )
        assertTrue(HookEventLog(file).readNew().single().stopHookActive)
    }

    @Test
    fun `a Notification payload yields Claude's own message`(@TempDir dir: Path) {
        val file = dir.resolve("events.jsonl")
        file.writeText(
            """{"session_id":"b44d9b22","transcript_path":"/t.jsonl","cwd":"/Users/dev/projects/acme/web",""" +
                """"prompt_id":"x","hook_event_name":"Notification",""" +
                """"message":"Claude needs your permission","notification_type":"permission_prompt"}""" + "\n",
        )

        val event = HookEventLog(file).readNew().single()
        assertEquals("Claude needs your permission", event.message)
        assertEquals("permission_prompt", event.notificationType)
        assertTrue(event.blocksOnUser())
    }

    @Test
    fun `a SessionStart payload yields the session title`(@TempDir dir: Path) {
        val file = dir.resolve("events.jsonl")
        file.writeText(
            """{"session_id":"580d3516","transcript_path":"/t.jsonl","cwd":"/repo","model":"opus",""" +
                """"hook_event_name":"SessionStart","source":"startup",""" +
                """"session_title":"JetBrains IDE plugin for WebStorm"}""" + "\n",
        )

        assertEquals(
            "JetBrains IDE plugin for WebStorm",
            HookEventLog(file).readNew().single().sessionTitle,
        )
    }

    @Test
    fun `a payload missing the optional fields parses with them absent`(@TempDir dir: Path) {
        val file = dir.resolve("events.jsonl")
        file.writeText("""{"session_id":"a","hook_event_name":"Stop","cwd":"/repo"}""" + "\n")

        val event = HookEventLog(file).readNew().single()
        assertNull(event.lastAssistantMessage)
        assertNull(event.sessionTitle)
        assertNull(event.message)
        assertFalse(event.stopHookActive)
    }

    @Test
    fun `a null or wrongly typed field is treated as absent`(@TempDir dir: Path) {
        val file = dir.resolve("events.jsonl")
        file.writeText(
            """{"session_id":"a","hook_event_name":"Stop","last_assistant_message":null,""" +
                """"session_title":{"nested":"object"},"stop_hook_active":"yes"}""" + "\n",
        )

        val event = HookEventLog(file).readNew().single()
        assertNull(event.lastAssistantMessage)
        assertNull(event.sessionTitle)
        // Gson coerces the string "yes" to false rather than throwing; either way it must
        // not take down the read.
        assertFalse(event.stopHookActive)
    }

    @Test
    fun `skipToEnd drops what is already there without returning it`(@TempDir dir: Path) {
        val file = dir.resolve("events.jsonl")
        file.writeText(
            """{"session_id":"old","hook_event_name":"Stop"}""" + "\n" +
                """{"session_id":"older","hook_event_name":"Stop"}""" + "\n",
        )

        val log = HookEventLog(file)
        log.skipToEnd()
        assertTrue(log.readNew().isEmpty(), "history must not be replayed as if it just happened")

        file.appendText("""{"session_id":"new","hook_event_name":"Stop"}""" + "\n")
        assertEquals(listOf("new"), log.readNew().map { it.sessionId })
    }

    @Test
    fun `skipToEnd on a log that does not exist yet is harmless`(@TempDir dir: Path) {
        val file = dir.resolve("absent.jsonl")
        val log = HookEventLog(file)
        log.skipToEnd()

        file.writeText("""{"session_id":"first","hook_event_name":"Stop"}""" + "\n")
        assertEquals(listOf("first"), log.readNew().map { it.sessionId })
    }
}
