package dev.andy.claudesessions.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.writeText

class ReviewReplyLogTest {

    private fun line(id: String, summary: String = "Fixed it.") =
        """{"comment_id":"$id","status":"done","summary":"$summary"}"""

    @Test
    fun `an absent file is not an error, because the agent may not have started`(@TempDir dir: Path) {
        assertTrue(ReviewReplyLog(dir.resolve("absent.jsonl")).readNew().isEmpty())
    }

    @Test
    fun `returns only what was appended since the last read`(@TempDir dir: Path) {
        val file = dir.resolve("replies.jsonl")
        file.writeText(line("C1.1") + "\n")
        val log = ReviewReplyLog(file)

        assertEquals(listOf("C1.1"), log.readNew().map { it.commentId })
        assertTrue(log.readNew().isEmpty())

        file.appendText(line("C2.1") + "\n")
        assertEquals(listOf("C2.1"), log.readNew().map { it.commentId })
    }

    @Test
    fun `prose between replies is skipped without losing the batch`(@TempDir dir: Path) {
        val file = dir.resolve("replies.jsonl")
        file.writeText(
            listOf(
                "Here is what I did:",
                line("C1.1"),
                "",
                "and also",
                line("C2.1"),
            ).joinToString("\n") + "\n",
        )

        assertEquals(listOf("C1.1", "C2.1"), ReviewReplyLog(file).readNew().map { it.commentId })
    }

    @Test
    fun `a line still being written is held over until it is complete`(@TempDir dir: Path) {
        val file = dir.resolve("replies.jsonl")
        file.writeText("""{"comment_id":"C1.1","status":"done","sum""")
        val log = ReviewReplyLog(file)

        assertTrue(log.readNew().isEmpty(), "half a line is not a reply yet")

        file.appendText("""mary":"Fixed it."}""" + "\n")
        assertEquals(listOf("C1.1"), log.readNew().map { it.commentId })
    }

    @Test
    fun `a pretty-printed object is recovered`(@TempDir dir: Path) {
        val file = dir.resolve("replies.jsonl")
        file.writeText(
            """
            {
              "comment_id": "C1.1",
              "status": "done",
              "summary": "Wrapped the fetch."
            }
            """.trimIndent() + "\n",
        )

        val reply = ReviewReplyLog(file).readNew().single()

        assertEquals("C1.1", reply.commentId)
        assertEquals("Wrapped the fetch.", reply.summary)
    }

    @Test
    fun `a file rewritten instead of appended to is read again from the start`(@TempDir dir: Path) {
        val file = dir.resolve("replies.jsonl")
        file.writeText(line("C1.1") + "\n" + line("C2.1") + "\n")
        val log = ReviewReplyLog(file)
        assertEquals(2, log.readNew().size)

        // The agent read the file and wrote it back, shorter than it was.
        file.writeText(line("C3.1") + "\n")

        assertEquals(listOf("C3.1"), log.readNew().map { it.commentId })
    }

    @Test
    fun `a saved position resumes mid-file rather than replaying it`(@TempDir dir: Path) {
        val file = dir.resolve("replies.jsonl")
        val first = line("C1.1") + "\n"
        file.writeText(first + line("C2.1") + "\n")

        val log = ReviewReplyLog(file, startOffset = first.toByteArray().size.toLong())

        assertEquals(listOf("C2.1"), log.readNew().map { it.commentId })
    }

    @Test
    fun `the read position is exposed so it can be persisted`(@TempDir dir: Path) {
        val file = dir.resolve("replies.jsonl")
        val text = line("C1.1") + "\n"
        file.writeText(text)
        val log = ReviewReplyLog(file)

        log.readNew()

        assertEquals(text.toByteArray().size.toLong(), log.offset)
    }

    @Test
    fun `a runaway file is abandoned rather than read forever`(@TempDir dir: Path) {
        val file = dir.resolve("replies.jsonl")
        file.writeText(line("C1.1", "x".repeat(300 * 1024)) + "\n")
        val log = ReviewReplyLog(file)

        assertTrue(log.readNew().isEmpty())
        assertTrue(log.overflowed)
    }
}
