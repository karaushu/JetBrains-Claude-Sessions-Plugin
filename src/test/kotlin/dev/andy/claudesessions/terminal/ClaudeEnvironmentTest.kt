package dev.andy.claudesessions.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClaudeEnvironmentTest {

    @Test
    fun `blanks the marker that disables transcript and status writing`() {
        val overrides = ClaudeEnvironment.overridesFor(
            mapOf("CLAUDE_CODE_CHILD_SESSION" to "1", "PATH" to "/usr/bin"),
        )
        assertEquals(mapOf("CLAUDE_CODE_CHILD_SESSION" to ""), overrides)
    }

    @Test
    fun `a clean environment produces no overrides`() {
        assertTrue(ClaudeEnvironment.overridesFor(mapOf("PATH" to "/usr/bin")).isEmpty())
    }

    @Test
    fun `only blanks markers that are actually present`() {
        val overrides = ClaudeEnvironment.overridesFor(
            mapOf("CLAUDECODE" to "1", "CLAUDE_CODE_ENTRYPOINT" to "claude-desktop"),
        )
        assertEquals(setOf("CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT"), overrides.keys)
        assertTrue(overrides.values.all { it.isEmpty() })
    }

    @Test
    fun `leaves the user's real Claude and Anthropic config alone`() {
        // Blanking these would break auth, model choice, or our own ability to find ~/.claude.
        val preserved = listOf(
            "ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL", "ANTHROPIC_MODEL",
            "CLAUDE_CONFIG_DIR", "ANTHROPIC_CONFIG_DIR",
        )
        val overrides = ClaudeEnvironment.overridesFor(preserved.associateWith { "x" })
        assertTrue(overrides.isEmpty(), "must not blank $overrides")
        preserved.forEach { assertFalse(it in ClaudeEnvironment.markers(), "$it must not be a marker") }
    }

    @Test
    fun `covers the markers this machine actually leaks`() {
        // Regression guard for the real-world case: a session started from a Claude Code
        // shell leaks these, and each one changes how a spawned session behaves.
        val leaked = listOf(
            "CLAUDE_CODE_CHILD_SESSION", "CLAUDECODE", "CLAUDE_CODE_SESSION_ID",
            "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_PID", "CLAUDE_EFFORT",
            "CLAUDE_CODE_HOST_SESSION_ID", "CLAUDE_CODE_EXECPATH",
        )
        leaked.forEach { assertTrue(it in ClaudeEnvironment.markers(), "$it should be scrubbed") }
    }
}
