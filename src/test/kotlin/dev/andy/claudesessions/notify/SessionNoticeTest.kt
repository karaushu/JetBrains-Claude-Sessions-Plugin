package dev.andy.claudesessions.notify

import dev.andy.claudesessions.hooks.HookEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The events here mirror payloads taken from a real `events.jsonl`, so the fields are the
 * ones Claude actually sends rather than the ones its docs imply.
 */
class SessionNoticeTest {

    private fun stop(
        message: String? = "Both pushed. CI is green.",
        stopHookActive: Boolean = false,
    ) = HookEvent(
        sessionId = "b44d9b22-0000-0000-0000-000000000000",
        eventName = "Stop",
        notificationType = null,
        cwd = "/Users/dev/projects/acme/web",
        lastAssistantMessage = message,
        stopHookActive = stopHookActive,
    )

    private fun notification(type: String?, message: String? = null) = HookEvent(
        sessionId = "b44d9b22-0000-0000-0000-000000000000",
        eventName = "Notification",
        notificationType = type,
        cwd = "/Users/dev/projects/acme/web",
        message = message,
    )

    private fun notice(event: HookEvent, name: String? = "Toast system", turnEnd: Boolean = true, prompt: Boolean = true) =
        SessionNotices.from(event, name, notifyOnTurnEnd = turnEnd, notifyOnPrompt = prompt)

    /** JUnit's assertNotNull returns void, and every assertion here is about the notice. */
    private fun must(event: HookEvent, name: String? = "Toast system", turnEnd: Boolean = true, prompt: Boolean = true) =
        notice(event, name, turnEnd, prompt)
            ?: error("expected a notification for ${event.eventName}/${event.notificationType}")

    @Test
    fun `a finished turn is announced with what Claude said`() {
        val result = must(stop())
        assertEquals(NoticeKind.TURN_END, result.kind)
        assertEquals("Claude finished · Toast system", result.title)
        assertEquals("Both pushed. CI is green.", result.body)
    }

    @Test
    fun `a turn a stop hook restarted is not announced`() {
        // stop_hook_active means Claude was sent back to work: the turn has not ended, and
        // saying it has would be simply false.
        assertNull(notice(stop(stopHookActive = true)))
    }

    @Test
    fun `a finished turn with nothing said still names the session`() {
        val result = must(stop(message = null))
        assertEquals("Claude finished · Toast system", result.title)
        assertEquals("", result.body)
    }

    @Test
    fun `a permission prompt is announced in Claude's own words`() {
        val result = must(notification("permission_prompt", "Claude needs your permission"))
        assertEquals(NoticeKind.NEEDS_INPUT, result.kind)
        assertEquals("Claude needs your permission", result.title)
        assertEquals("Toast system", result.body)
    }

    @Test
    fun `a plan waiting for approval is announced`() {
        val result = must(notification("permission_prompt", "Claude Code needs your approval for the plan"))
        assertEquals("Claude Code needs your approval for the plan", result.title)
    }

    @Test
    fun `a blocking notification with no message falls back to our own wording`() {
        val result = must(notification("elicitation_dialog"))
        assertEquals("Claude needs your input", result.title)
    }

    @Test
    fun `an idle nudge is not announced`() {
        // idle_prompt fires a minute after Claude stopped. Announcing it would say the same
        // thing as the turn-end notification, only later.
        assertNull(notice(notification("idle_prompt", "Claude is waiting for your input")))
    }

    @Test
    fun `a completed background agent counts as a finished turn`() {
        val result = must(notification("agent_completed", "Agent finished"))
        assertEquals(NoticeKind.TURN_END, result.kind)
    }

    @Test
    fun `an unrecognised notification type is ignored rather than guessed at`() {
        assertNull(notice(notification("auth_success")))
        assertNull(notice(notification(null)))
    }

    @Test
    fun `events that are neither are ignored`() {
        for (name in listOf("SessionStart", "SessionEnd", "UserPromptSubmit", "PreToolUse")) {
            assertNull(
                notice(HookEvent("s", name, null, "/repo")),
                "$name should not be announced",
            )
        }
    }

    @Test
    fun `each kind can be switched off independently`() {
        assertNull(notice(stop(), turnEnd = false))
        must(notification("permission_prompt"), turnEnd = false)

        assertNull(notice(notification("permission_prompt"), prompt = false))
        must(stop(), prompt = false)

        assertNull(notice(stop(), turnEnd = false, prompt = false))
        assertNull(notice(notification("permission_prompt"), turnEnd = false, prompt = false))
    }

    @Test
    fun `a session with no known name is still identified`() {
        val result = must(stop(), name = null)
        assertEquals("Claude finished · Claude session", result.title)
    }

    @Test
    fun `model output is flattened before it reaches an OS notification`() {
        // Claude's closing message is multi-line markdown. A newline in a notification title
        // is not a formatting problem, it is a way to smuggle a second line into it.
        val raw = "Done.\n\n- **api** `a1b2c3d..e4f5a6b`\n- web CI: ✅"
        val result = must(stop(message = raw))
        assertTrue('\n' !in result.body, "body must be one line: ${result.body}")
        assertEquals("Done. - **api** `a1b2c3d..e4f5a6b` - web CI: ✅", result.body)
    }

    @Test
    fun `a very long closing message is truncated`() {
        val result = must(stop(message = "x".repeat(500)))
        assertTrue(result.body.length <= 120, "was ${result.body.length}")
        assertTrue(result.body.endsWith("…"))
    }

    @Test
    fun `the session name is carried separately from the wording`() {
        // The tab opened from a notification is named from this, so it must be the session's
        // name and not the notification's text.
        assertEquals("Toast system", must(stop()).sessionName)
        assertEquals(
            "Toast system",
            must(notification("permission_prompt", "Claude needs your permission")).sessionName,
        )
    }
}
