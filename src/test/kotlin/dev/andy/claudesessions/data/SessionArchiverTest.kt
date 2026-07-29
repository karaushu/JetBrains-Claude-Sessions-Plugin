package dev.andy.claudesessions.data

import dev.andy.claudesessions.model.LiveStatus
import dev.andy.claudesessions.model.SessionItem
import dev.andy.claudesessions.model.SessionSummary
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

class SessionArchiverTest {

    private fun item(transcript: Path, sessionId: String, live: Boolean = false) = SessionItem(
        summary = SessionSummary(
            sessionId = sessionId,
            transcript = transcript,
            cwd = "/repo",
            gitBranch = "main",
            title = "A session",
            startedAt = null,
            lastActivity = Instant.EPOCH,
            sizeBytes = 1,
        ),
        live = if (live) LiveStatus(1, sessionId, "/repo", "idle", null, "cli", "interactive", null, null) else null,
    )

    @Test
    fun `a running session is never archived, because it is still being written to`() {
        val id = "11111111-2222-3333-4444-555555555555"
        assertFalse(SessionArchiver.canArchive(item(Path.of("/tmp/$id.jsonl"), id, live = true)))
    }

    @Test
    fun `a session whose transcript is gone cannot be archived`() {
        val id = "22222222-2222-3333-4444-555555555555"
        assertFalse(SessionArchiver.canArchive(item(Path.of("/tmp/definitely-absent-$id.jsonl"), id)))
    }

    @Test
    fun `archiving moves the transcript and restore puts it back`(@org.junit.jupiter.api.io.TempDir dir: Path) {
        // Exercise move/restore against a temp tree by pointing the session at it. The
        // archive directory itself is fixed, so this also proves restore returns the file
        // to its original location rather than a guessed one.
        val id = "33333333-2222-3333-4444-555555555555"
        val projects = dir.resolve("projects/-repo").also { it.createDirectories() }
        val transcript = projects.resolve("$id.jsonl")
        transcript.writeText("""{"type":"user","cwd":"/repo"}""" + "\n")

        val session = item(transcript, id)
        assertTrue(SessionArchiver.canArchive(session))

        val archived = SessionArchiver.archive(session)
        requireNotNull(archived) { "archive should succeed" }
        assertFalse(transcript.exists(), "transcript should have moved")
        assertTrue(archived.to.exists(), "archived copy should exist")

        assertTrue(SessionArchiver.restore(archived))
        assertTrue(transcript.exists(), "restore should put it back")
        assertFalse(archived.to.exists())
    }

    @Test
    fun `archiving twice does not clobber the first copy`(@org.junit.jupiter.api.io.TempDir dir: Path) {
        val id = "44444444-2222-3333-4444-555555555555"
        val projects = dir.resolve("projects/-repo").also { it.createDirectories() }

        val first = projects.resolve("$id.jsonl").also { it.writeText("one\n") }
        val archivedFirst = requireNotNull(SessionArchiver.archive(item(first, id)))

        val second = projects.resolve("$id.jsonl").also { it.writeText("two\n") }
        val archivedSecond = requireNotNull(SessionArchiver.archive(item(second, id)))

        assertTrue(archivedFirst.to.exists())
        assertTrue(archivedSecond.to.exists())
        assertFalse(archivedFirst.to == archivedSecond.to, "second archive must not overwrite the first")

        // Leave no litter behind for the next run.
        java.nio.file.Files.deleteIfExists(archivedFirst.to)
        java.nio.file.Files.deleteIfExists(archivedSecond.to)
    }

    @Test
    fun `sidecar directory travels with the transcript`(@org.junit.jupiter.api.io.TempDir dir: Path) {
        val id = "55555555-2222-3333-4444-555555555555"
        val projects = dir.resolve("projects/-repo").also { it.createDirectories() }
        val transcript = projects.resolve("$id.jsonl").also { it.writeText("x\n") }
        // Subagent transcripts and offloaded tool output live here.
        projects.resolve("$id/subagents").createDirectories()

        val archived = requireNotNull(SessionArchiver.archive(item(transcript, id)))
        assertFalse(projects.resolve(id).exists(), "sidecar should have moved too")
        requireNotNull(archived.sidecar)
        assertTrue(archived.sidecar!!.second.exists())

        assertTrue(SessionArchiver.restore(archived))
        assertTrue(projects.resolve("$id/subagents").exists(), "sidecar should come back")
    }
}
