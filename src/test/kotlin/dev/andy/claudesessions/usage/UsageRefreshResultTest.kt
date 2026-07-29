package dev.andy.claudesessions.usage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UsageRefreshResultTest {

    @Test
    fun `success and already-fresh are not problems`() {
        assertNull(UsageRefreshResult.Success.problem())
        // Claude declining to rewrite a young cache is expected, not a failure.
        assertNull(UsageRefreshResult.AlreadyFresh.problem())
    }

    @Test
    fun `every failure has something to show the user`() {
        // A silent failure showing a stale figure as current is the thing to avoid.
        for (result in listOf(
            UsageRefreshResult.ClaudeNotFound,
            UsageRefreshResult.TimedOut,
            UsageRefreshResult.Failed(1, "boom"),
            UsageRefreshResult.Crashed("no such file"),
        )) {
            assertNotNull(result.problem(), "$result should explain itself")
        }
    }

    @Test
    fun `the exit code is surfaced, since it is the useful detail`() {
        assertEquals("Claude exited with code 42", UsageRefreshResult.Failed(42, "").problem())
    }

    @Test
    fun `the refresh suppresses persistence, or it would litter the session list`() {
        // Verified against the CLI: with this set, a /usage run added zero transcripts;
        // without it, one per run — which at a five-minute cadence is ~288 a day.
        val env = UsageRefresher.refreshEnvironment()
        assertEquals("1", env["CLAUDE_CODE_SKIP_PROMPT_HISTORY"])
    }

    @Test
    fun `the skip flag wins over the inherited-marker scrub`() {
        // ClaudeEnvironment blanks that same variable for interactive sessions, so the
        // explicit value has to be applied last or the refresh would persist after all.
        val env = UsageRefresher.refreshEnvironment()
        assertEquals(true, env["CLAUDE_CODE_SKIP_PROMPT_HISTORY"]?.isNotEmpty())
    }

    @Test
    fun `claude is looked for beyond PATH, because a GUI IDE may not have it`() {
        val candidates = UsageRefresher.candidatePaths()
        assertEquals(true, candidates.any { it.endsWith("/.local/bin/claude") })
        assertEquals(true, candidates.any { it.startsWith("/opt/homebrew/") })
    }
}
