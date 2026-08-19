package dev.andy.claudesessions.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewThreadTest {

    private fun thread(): ReviewThread = ReviewThread(
        id = "C1",
        anchor = ReviewAnchor(path = "src/a.ts", line = 10, lineText = "const x = 1"),
        status = ReviewThreadStatus.OPEN,
        comments = listOf(ReviewComment("C1.1", CommentAuthor.USER, "fix this", 1_000)),
        createdAtMillis = 1_000,
    )

    private fun reply(id: String, text: String = "done it") =
        ReviewComment(id, CommentAuthor.AGENT, text, 2_000, ReplyOutcome.DONE)

    @Test
    fun `only an open thread is sendable`() {
        val open = thread()
        assertTrue(open.isSendable)
        assertFalse(open.markedSent("r1", "s1", 1_100).isSendable)
        assertFalse(open.closed().isSendable)
    }

    @Test
    fun `sending awaits an answer to the last user comment`() {
        val sent = thread().markedSent("r1", "s1", 1_100)

        assertEquals(ReviewThreadStatus.SENT, sent.status)
        assertEquals("C1.1", sent.awaitingReplyTo)
        assertEquals("r1", sent.roundId)
        assertEquals("s1", sent.sentToSessionId)
    }

    @Test
    fun `the awaited reply answers the thread`() {
        val answered = thread().markedSent("r1", "s1", 1_100).withAgentReply(reply("C1.1"))

        assertEquals(ReviewThreadStatus.ANSWERED, answered.status)
        assertNull(answered.awaitingReplyTo, "nothing is outstanding once it is answered")
        assertNull(answered.problem)
        assertEquals(CommentAuthor.AGENT, answered.comments.last().author)
    }

    @Test
    fun `a further comment on an answered thread sends it again with its whole history`() {
        val answered = thread().markedSent("r1", "s1", 1_100).withAgentReply(reply("C1.1"))
        val reopened = answered.withUserComment("C1.2", "still wrong", 3_000)

        assertEquals(ReviewThreadStatus.OPEN, reopened.status)
        assertTrue(reopened.isSendable)
        assertEquals(3, reopened.comments.size, "the agent must see what it already said")
        assertEquals("C1.2", reopened.markedSent("r2", "s1", 3_100).awaitingReplyTo)
    }

    @Test
    fun `a closed thread reopens when the user writes on the line again`() {
        val reopened = thread().closed().withUserComment("C1.2", "again", 3_000)

        assertEquals(ReviewThreadStatus.OPEN, reopened.status)
        assertNull(reopened.problem)
    }

    @Test
    fun `a reply to a closed thread is kept but does not reopen it`() {
        val closed = thread().markedSent("r1", "s1", 1_100).closed()
        val late = closed.withAgentReply(reply("C1.1"))

        assertEquals(ReviewThreadStatus.CLOSED, late.status, "the user closed it deliberately")
        assertEquals(2, late.comments.size, "a reply is never discarded")
        assertEquals(ProtocolProblem.UNEXPECTED_REPLY, late.problem)
    }

    @Test
    fun `a reply to a comment nobody is waiting on is flagged as late`() {
        val answered = thread().markedSent("r1", "s1", 1_100).withAgentReply(reply("C1.1"))
        val second = answered.withAgentReply(reply("C1.1", "and again"))

        assertEquals(ProtocolProblem.LATE_REPLY, second.problem)
        assertEquals(3, second.comments.size)
    }

    @Test
    fun `a failed send leaves the comments alone and puts the thread back in the queue`() {
        val rolledBack = thread().markedSent("r1", "s1", 1_100).rolledBack()

        assertEquals(ReviewThreadStatus.OPEN, rolledBack.status)
        assertNull(rolledBack.roundId)
        assertNull(rolledBack.awaitingReplyTo)
        assertEquals(1, rolledBack.comments.size)
    }

    @Test
    fun `re-anchoring to nothing detaches the thread rather than moving it`() {
        val lost = thread().reanchored(null)

        assertTrue(lost.anchorLost)
        assertEquals(10, lost.anchor.line, "the last known line is kept so it can still be shown")

        val found = lost.reanchored(14)
        assertFalse(found.anchorLost)
        assertEquals(14, found.anchor.line)
    }

    @Test
    fun `re-anchoring shifts the hunk numbering with the line`() {
        // The hunk shows capture-time code; its numbering must follow the anchor, or the `>`
        // marker lands on a neighbouring row and the header contradicts the hunk.
        val before = thread()
        val moved = before.reanchored(14)

        assertEquals(14, moved.anchor.line)
        assertEquals(
            14 - before.anchor.line,
            moved.anchor.contextStartLine - before.anchor.contextStartLine,
        )
    }

    @Test
    fun `an already applied reply is recognised so a replayed file changes nothing`() {
        val answered = thread().markedSent("r1", "s1", 1_100).withAgentReply(reply("C1.1"))

        assertTrue(answered.hasReply("C1.1", "done it"))
        assertFalse(answered.hasReply("C1.1", "something else"))
        assertFalse(thread().hasReply("C1.1", "done it"), "the user's own comment is not a reply")
    }
}
