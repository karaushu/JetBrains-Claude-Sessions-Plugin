package dev.andy.claudesessions.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClaudePathsTest {

    @Test
    fun `encodes a plain absolute path`() {
        assertEquals(
            "-Users-dev-projects-web-admin",
            ClaudePaths.encodeProjectDir("/Users/dev/projects/web/admin"),
        )
    }

    @Test
    fun `encodes a dot directory as a double dash`() {
        // Verified against the real ~/.claude/projects layout.
        assertEquals(
            "-Users-dev--superset-worktrees-abc",
            ClaudePaths.encodeProjectDir("/Users/dev/.superset/worktrees/abc"),
        )
    }

    @Test
    fun `encodes spaces as dashes`() {
        assertEquals(
            "-Users-dev-Downloads-skills-for-ai-chat",
            ClaudePaths.encodeProjectDir("/Users/dev/Downloads/skills for ai chat"),
        )
    }

    @Test
    fun `encoding collides for distinct paths, which is why cwd must be confirmed separately`() {
        // Both encode identically; the directory name alone cannot disambiguate them.
        val viaSlash = ClaudePaths.encodeProjectDir("/Users/dev/projects/acme/apps/admin")
        val viaDash = ClaudePaths.encodeProjectDir("/Users/dev/projects/acme-apps/admin")
        assertEquals(viaSlash, viaDash)
    }
}
