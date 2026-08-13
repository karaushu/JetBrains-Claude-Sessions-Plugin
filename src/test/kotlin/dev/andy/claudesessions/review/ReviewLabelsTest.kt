package dev.andy.claudesessions.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewLabelsTest {

    private fun thread(
        status: ReviewThreadStatus = ReviewThreadStatus.OPEN,
        line: Int = 141,
        anchorLost: Boolean = false,
        problem: ProtocolProblem? = null,
        reply: ReviewComment? = null,
    ) = ReviewThread(
        id = "C1",
        anchor = ReviewAnchor("src/a.ts", line, "const x = 1"),
        status = status,
        comments = listOfNotNull(
            ReviewComment("C1.1", CommentAuthor.USER, "fix", 1_000),
            reply,
        ),
        createdAtMillis = 1_000,
        anchorLost = anchorLost,
        problem = problem,
    )

    @Test
    fun `the button always names the session it would send to`() {
        assertEquals("Send 3 notes → Auth flow", ReviewLabels.sendButton(3, "Auth flow"))
        assertEquals("Send 1 note → Auth flow", ReviewLabels.sendButton(1, "Auth flow"))
    }

    @Test
    fun `with no session open the button says so instead of naming nothing`() {
        assertEquals("Send 2 notes — no session open", ReviewLabels.sendButton(2, null))
    }

    @Test
    fun `with nothing written the button does not offer to send`() {
        assertEquals("No review notes", ReviewLabels.sendButton(0, "Auth flow"))
        assertFalse(ReviewLabels.sendButton(0, null).contains("Send"))
    }

    @Test
    fun `notes already in flight read as nothing new rather than as none at all`() {
        assertEquals("Nothing new to send", ReviewLabels.sendButton(0, "Auth flow", openCount = 3))
    }

    @Test
    fun `the compact label is a count, or nothing at all`() {
        assertEquals("3", ReviewLabels.sendButtonCompact(3))
        assertEquals("", ReviewLabels.sendButtonCompact(0))
    }

    @Test
    fun `the description says how to make a note when there is none`() {
        assertTrue(ReviewLabels.sendDescription(0, null).contains("click + to write a note"))
    }

    @Test
    fun `a long session title cannot stretch the button off the toolbar`() {
        val label = ReviewLabels.sendButton(1, "a".repeat(200))

        assertTrue(label.length < 60, label)
        assertTrue(label.endsWith("…"), label)
    }

    @Test
    fun `the draft header names the line in the numbering the user sees`() {
        assertEquals("Note for Claude · line 142", ReviewLabels.draftHeader(141))
    }

    @Test
    fun `the note header shows a one-based line number`() {
        assertEquals("Note · line 142", ReviewLabels.threadHeader(thread()))
    }

    @Test
    fun `a detached note says the line is gone rather than naming a wrong one`() {
        assertEquals("Note · line no longer found", ReviewLabels.threadHeader(thread(anchorLost = true)))
    }

    @Test
    fun `an unsent note says so`() {
        assertEquals("Not sent yet", ReviewLabels.status(thread(), null))
    }

    @Test
    fun `a sent note names where it went`() {
        assertEquals(
            "Sent to Auth flow · waiting",
            ReviewLabels.status(thread(ReviewThreadStatus.SENT), "Auth flow"),
        )
    }

    @Test
    fun `an answered note reports the outcome and the files touched`() {
        val reply = ReviewComment(
            id = "C1.1",
            author = CommentAuthor.AGENT,
            text = "Done.",
            createdAtMillis = 2_000,
            outcome = ReplyOutcome.DONE,
            touchedFiles = listOf("src/a.ts"),
        )

        val status = ReviewLabels.status(thread(ReviewThreadStatus.ANSWERED, reply = reply), "Auth")

        assertEquals("Claude answered · src/a.ts", status)
    }

    @Test
    fun `a skipped note does not read as done`() {
        val reply = ReviewComment("C1.1", CommentAuthor.AGENT, "No.", 2_000, ReplyOutcome.SKIPPED)

        assertEquals(
            "Claude skipped this",
            ReviewLabels.status(thread(ReviewThreadStatus.ANSWERED, reply = reply), "Auth"),
        )
    }

    @Test
    fun `a note the agent never answered says exactly that`() {
        val status = ReviewLabels.status(
            thread(ReviewThreadStatus.SENT, problem = ProtocolProblem.NO_REPLY),
            "Auth",
        )

        assertEquals("Claude finished without answering this", status)
    }

    @Test
    fun `authors are named for a reader, not by enum`() {
        assertEquals("You", ReviewLabels.author(ReviewComment("C1.1", CommentAuthor.USER, "x", 0)))
        assertEquals("Claude", ReviewLabels.author(ReviewComment("C1.1", CommentAuthor.AGENT, "x", 0)))
    }
}
