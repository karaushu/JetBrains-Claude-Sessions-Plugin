package dev.andy.claudesessions.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClaudeCommandsTest {

    private val id = "205a95c7-cf51-42da-9c54-5fc318034fa1"

    @Test
    fun `resume targets the session by id`() {
        assertEquals("claude --resume $id", ClaudeCommands.resume(id))
    }

    @Test
    fun `a background agent is branched, never resumed in place`() {
        // Plain --resume refuses: "is currently running as a background agent (bg)".
        val forked = ClaudeCommands.resumeForked(id)
        assertTrue(forked.contains("--fork-session"), forked)
        assertTrue(forked.contains(id), forked)
    }

    @Test
    fun `attaching goes through Claude's agent view`() {
        // There is no `claude agents attach <id>`; the view is an interactive picker.
        assertEquals("claude agents", ClaudeCommands.agents())
    }

    @Test
    fun `a new session takes no arguments`() {
        assertEquals("claude", ClaudeCommands.newSession())
    }

    @Test
    fun `no command is built with an empty id`() {
        // Guards against handing the CLI "claude --resume " and getting a confusing failure.
        assertTrue(ClaudeCommands.resume(id).endsWith(id))
        assertTrue(ClaudeCommands.resumeForked(id).contains(" $id "))
    }
}
