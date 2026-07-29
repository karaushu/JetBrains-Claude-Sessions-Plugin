package dev.andy.claudesessions.settings

import dev.andy.claudesessions.usage.UsageSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class ClaudeSessionsSettingsTest {

    @Test
    fun `background fetching is on by default, at ten minutes`() {
        val settings = ClaudeSessionsSettings()
        assertTrue(settings.usageAutoRefresh)
        assertEquals(10, settings.usageRefreshMinutes)
        assertEquals(Duration.ofMinutes(10), settings.usageRefreshInterval)
    }

    @Test
    fun `an interval below Claude's write floor is raised to it`() {
        // Anything shorter starts a process that Claude answers by discarding the result.
        val settings = ClaudeSessionsSettings()
        settings.usageRefreshMinutes = 1
        assertEquals(ClaudeSessionsSettings.MIN_REFRESH_MINUTES, settings.usageRefreshMinutes)
        settings.usageRefreshMinutes = 0
        assertEquals(ClaudeSessionsSettings.MIN_REFRESH_MINUTES, settings.usageRefreshMinutes)
        settings.usageRefreshMinutes = -30
        assertEquals(ClaudeSessionsSettings.MIN_REFRESH_MINUTES, settings.usageRefreshMinutes)
    }

    @Test
    fun `an absurd interval is capped`() {
        val settings = ClaudeSessionsSettings()
        settings.usageRefreshMinutes = 100_000
        assertEquals(ClaudeSessionsSettings.MAX_REFRESH_MINUTES, settings.usageRefreshMinutes)
    }

    @Test
    fun `the floor is derived from Claude's behaviour rather than guessed`() {
        assertEquals(
            UsageSnapshot.CLAUDE_WRITE_FLOOR.toMinutes().toInt(),
            ClaudeSessionsSettings.MIN_REFRESH_MINUTES,
        )
    }

    @Test
    fun `a hand-edited config is clamped on read, not just when set through the UI`() {
        val settings = ClaudeSessionsSettings()
        // Simulates claudeSessions.xml edited by hand to something unusable.
        settings.loadState(ClaudeSessionsSettings.State().apply { usageRefreshMinutes = 1 })
        assertEquals(ClaudeSessionsSettings.MIN_REFRESH_MINUTES, settings.usageRefreshMinutes)
    }

    @Test
    fun `settings survive a state round trip`() {
        val original = ClaudeSessionsSettings().apply {
            usageAutoRefresh = false
            usageRefreshMinutes = 45
        }
        val restored = ClaudeSessionsSettings().apply { loadState(original.state) }

        assertEquals(false, restored.usageAutoRefresh)
        assertEquals(45, restored.usageRefreshMinutes)
    }
}
