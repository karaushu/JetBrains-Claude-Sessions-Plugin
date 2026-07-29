package dev.andy.claudesessions.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class SessionIndexerTest {

    private val sessionId = "11111111-2222-3333-4444-555555555555"

    private fun write(dir: Path, vararg lines: String): Path {
        val file = dir.resolve("$sessionId.jsonl")
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        return file
    }

    @Test
    fun `takes the last occurrence of a rewritten title record`(@TempDir dir: Path) {
        // These index records are rewritten in place, so later lines must win.
        write(
            dir,
            """{"type":"user","sessionId":"$sessionId","cwd":"/tmp/proj","gitBranch":"main","timestamp":"2026-07-01T10:00:00Z"}""",
            """{"type":"ai-title","aiTitle":"First guess","sessionId":"$sessionId"}""",
            """{"type":"ai-title","aiTitle":"Better title","sessionId":"$sessionId"}""",
        )

        val summary = SessionIndexer().indexDirectory(dir).single()
        assertEquals("Better title", summary.title)
    }

    @Test
    fun `prefers a user set custom title over the generated one`(@TempDir dir: Path) {
        write(
            dir,
            """{"type":"user","sessionId":"$sessionId","cwd":"/tmp/proj","timestamp":"2026-07-01T10:00:00Z"}""",
            """{"type":"ai-title","aiTitle":"Generated"}""",
            """{"type":"custom-title","customTitle":"Mine"}""",
        )

        assertEquals("Mine", SessionIndexer().indexDirectory(dir).single().title)
    }

    @Test
    fun `falls back through slug to last prompt`(@TempDir dir: Path) {
        write(
            dir,
            """{"type":"user","sessionId":"$sessionId","cwd":"/tmp/p","slug":"add-dark-mode-toggle","timestamp":"2026-07-01T10:00:00Z"}""",
        )
        assertEquals("Add dark mode toggle", SessionIndexer().indexDirectory(dir).single().title)

        val other = dir.resolve("sub").also { it.toFile().mkdirs() }
        write(
            other,
            """{"type":"user","sessionId":"$sessionId","cwd":"/tmp/p","timestamp":"2026-07-01T10:00:00Z"}""",
            """{"type":"last-prompt","lastPrompt":"fix the flaky test"}""",
        )
        assertEquals("fix the flaky test", SessionIndexer().indexDirectory(other).single().title)
    }

    @Test
    fun `relocated cwd overrides the envelope cwd`(@TempDir dir: Path) {
        write(
            dir,
            """{"type":"user","sessionId":"$sessionId","cwd":"/tmp/original","timestamp":"2026-07-01T10:00:00Z"}""",
            """{"type":"relocated","relocatedCwd":"/tmp/moved"}""",
        )

        assertEquals("/tmp/moved", SessionIndexer().indexDirectory(dir).single().cwd)
    }

    @Test
    fun `skips oversized lines without failing the whole file`(@TempDir dir: Path) {
        // Mimics an assistant record carrying an inline base64 image.
        val huge = """{"type":"assistant","blob":"${"A".repeat(200_000)}"}"""
        write(
            dir,
            """{"type":"user","sessionId":"$sessionId","cwd":"/tmp/proj","gitBranch":"feat/x","timestamp":"2026-07-01T10:00:00Z"}""",
            huge,
            """{"type":"ai-title","aiTitle":"Survived the blob"}""",
        )

        val summary = SessionIndexer().indexDirectory(dir).single()
        assertEquals("Survived the blob", summary.title)
        assertEquals("feat/x", summary.gitBranch)
        assertEquals("/tmp/proj", summary.cwd)
    }

    @Test
    fun `parses the first record even when it is large`(@TempDir dir: Path) {
        // cwd and gitBranch live on envelope records, which can exceed the normal cap.
        val padding = "B".repeat(20_000)
        write(
            dir,
            """{"type":"user","sessionId":"$sessionId","pad":"$padding","cwd":"/tmp/big","gitBranch":"main","timestamp":"2026-07-01T10:00:00Z"}""",
        )

        val summary = SessionIndexer().indexDirectory(dir).single()
        assertEquals("/tmp/big", summary.cwd)
        assertEquals("main", summary.gitBranch)
    }

    @Test
    fun `tolerates malformed and empty lines`(@TempDir dir: Path) {
        write(
            dir,
            "",
            "not json at all",
            """{"type":"user","sessionId":"$sessionId","cwd":"/tmp/proj","timestamp":"2026-07-01T10:00:00Z"}""",
            "{ truncated",
            """{"type":"ai-title","aiTitle":"Still fine"}""",
        )

        val summary = SessionIndexer().indexDirectory(dir).single()
        assertEquals("Still fine", summary.title)
    }

    @Test
    fun `derives session id from the filename and records start time`(@TempDir dir: Path) {
        write(
            dir,
            """{"type":"user","sessionId":"ignored-field-value","cwd":"/tmp/p","timestamp":"2026-07-01T10:00:00Z"}""",
        )

        val summary = SessionIndexer().indexDirectory(dir).single()
        assertEquals(sessionId, summary.sessionId)
        assertEquals("2026-07-01T10:00:00Z", summary.startedAt.toString())
        assertTrue(summary.sizeBytes > 0)
    }

    @Test
    fun `title is null when nothing identifies the session`(@TempDir dir: Path) {
        write(dir, """{"type":"user","cwd":"/tmp/p","timestamp":"2026-07-01T10:00:00Z"}""")
        assertNull(SessionIndexer().indexDirectory(dir).single().title)
    }

    @Test
    fun `ignores non jsonl files`(@TempDir dir: Path) {
        dir.resolve("notes.md").writeText("hello")
        dir.resolve("$sessionId.orphaned-1234-0").writeText("""{"type":"ai-title","aiTitle":"x"}""")
        write(dir, """{"type":"user","cwd":"/tmp/p","timestamp":"2026-07-01T10:00:00Z"}""")

        assertEquals(1, SessionIndexer().indexDirectory(dir).size)
    }

    @Test
    fun `missing directory yields no sessions`(@TempDir dir: Path) {
        assertTrue(SessionIndexer().indexDirectory(dir.resolve("nope")).isEmpty())
    }

    @Test
    fun `reuses the cached summary for an unchanged file`(@TempDir dir: Path) {
        val file = write(
            dir,
            """{"type":"user","sessionId":"$sessionId","cwd":"/tmp/p","timestamp":"2026-07-01T10:00:00Z"}""",
            """{"type":"ai-title","aiTitle":"Cached"}""",
        )
        val indexer = SessionIndexer()

        val first = indexer.summarise(file)
        val second = indexer.summarise(file)

        assertNotNull(first)
        assertEquals(first, second)
    }
}
