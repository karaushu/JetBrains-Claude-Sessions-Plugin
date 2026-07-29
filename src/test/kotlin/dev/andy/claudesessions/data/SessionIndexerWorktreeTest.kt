package dev.andy.claudesessions.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class SessionIndexerWorktreeTest {

    private val sessionId = "6895353a-f134-4f12-8914-6c21f94af5c5"

    private fun write(dir: Path, vararg lines: String): Path {
        val file = dir.resolve("$sessionId.jsonl")
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        return file
    }

    /** Copied from a real transcript, so the field names are Claude's rather than assumed. */
    private val worktreeRecord =
        """{"type":"worktree-state","worktreeSession":{"originalCwd":"/repo",""" +
            """"preEnterOriginalCwd":"/repo",""" +
            """"worktreePath":"/repo/.claude/worktrees/worktree-1",""" +
            """"worktreeName":"worktree-1","worktreeBranch":"worktree-1",""" +
            """"sessionId":"$sessionId","enteredExisting":true}}"""

    private fun envelope(cwd: String) =
        """{"type":"user","sessionId":"$sessionId","cwd":"$cwd","timestamp":"2026-07-01T10:00:00Z"}"""

    @Test
    fun `reads the worktree a session runs in`(@TempDir dir: Path) {
        write(dir, envelope("/repo/.claude/worktrees/worktree-1"), worktreeRecord)

        val summary = SessionIndexer().indexDirectory(dir).single()
        assertEquals("worktree-1", summary.worktreeName)
        // This is what ties a worktree session back to the project it was started for.
        assertEquals("/repo", summary.originalCwd)
    }

    @Test
    fun `a session outside a worktree reports none`(@TempDir dir: Path) {
        write(dir, envelope("/repo"))

        val summary = SessionIndexer().indexDirectory(dir).single()
        assertNull(summary.worktreeName)
        assertNull(summary.originalCwd)
    }

    @Test
    fun `the worktree record does not disturb the cwd or title`(@TempDir dir: Path) {
        write(
            dir,
            envelope("/repo/.claude/worktrees/worktree-1"),
            worktreeRecord,
            """{"type":"ai-title","aiTitle":"Try something risky"}""",
        )

        val summary = SessionIndexer().indexDirectory(dir).single()
        assertEquals("/repo/.claude/worktrees/worktree-1", summary.cwd)
        assertEquals("Try something risky", summary.title)
        assertEquals("worktree-1", summary.worktreeName)
    }

    @Test
    fun `a malformed worktree record is ignored rather than failing the file`(@TempDir dir: Path) {
        write(
            dir,
            envelope("/repo"),
            """{"type":"worktree-state"}""",
            """{"type":"worktree-state","worktreeSession":null}""",
            """{"type":"ai-title","aiTitle":"Still fine"}""",
        )

        val summary = SessionIndexer().indexDirectory(dir).single()
        assertEquals("Still fine", summary.title)
        assertNull(summary.worktreeName)
    }
}
