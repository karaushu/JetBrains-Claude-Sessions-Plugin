package dev.andy.claudesessions.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewReplyTest {

    @Test
    fun `parses the line the review file asks for`() {
        val reply = ReviewReply.parse(
            """{"comment_id":"C7.2","status":"done","summary":"Wrapped the fetch.","files":["src/a.ts"]}"""
        )!!

        assertEquals("C7.2", reply.commentId)
        assertEquals("Wrapped the fetch.", reply.summary)
        assertEquals(ReplyOutcome.DONE, reply.outcome)
        assertEquals(listOf("src/a.ts"), reply.touchedFiles)
    }

    @Test
    fun `accepts the field names a model reaches for instead`() {
        val reply = ReviewReply.parse("""{"id":"C7.2","reply":"Did it.","file":"src/a.ts"}""")!!

        assertEquals("C7.2", reply.commentId)
        assertEquals("Did it.", reply.summary)
        assertEquals(listOf("src/a.ts"), reply.touchedFiles, "a bare string is wrapped")
    }

    @Test
    fun `a quoted id is the same id`() {
        assertEquals("C7.2", ReviewReply.parse("""{"comment_id":"`C7.2`","summary":"x"}""")!!.commentId)
        assertEquals("C7", ReviewReply.parse("""{"comment_id":"#C7.","summary":"x"}""")!!.commentId)
    }

    @Test
    fun `a missing status means it was done`() {
        assertEquals(ReplyOutcome.DONE, ReviewReply.parse("""{"id":"C1.1","summary":"x"}""")!!.outcome)
    }

    @Test
    fun `an invented status degrades rather than dropping the reply`() {
        val reply = ReviewReply.parse("""{"id":"C1.1","summary":"x","status":"partially-done"}""")!!

        assertEquals(ReplyOutcome.DONE, reply.outcome)
    }

    @Test
    fun `a status the protocol does define is honoured`() {
        val reply = ReviewReply.parse("""{"id":"C1.1","summary":"Cannot.","status":"SKIPPED"}""")!!

        assertEquals(ReplyOutcome.SKIPPED, reply.outcome)
    }

    @Test
    fun `a line with no id or no summary is not a reply`() {
        assertNull(ReviewReply.parse("""{"summary":"I fixed everything."}"""))
        assertNull(ReviewReply.parse("""{"comment_id":"C1.1"}"""))
        assertNull(ReviewReply.parse("""{"comment_id":"","summary":"x"}"""))
    }

    @Test
    fun `prose and torn lines are skipped without complaint`() {
        assertNull(ReviewReply.parse("I have finished the review."))
        assertNull(ReviewReply.parse("""{"comment_id":"C1.1","summ"""))
        assertNull(ReviewReply.parse(""))
    }

    @Test
    fun `a path outside the project is dropped`() {
        val reply = ReviewReply.parse(
            """{"id":"C1.1","summary":"x","files":["/etc/passwd","../../secrets","src/ok.ts"]}"""
        )!!

        assertEquals(listOf("src/ok.ts"), reply.touchedFiles)
    }

    @Test
    fun `an essay of a summary is capped`() {
        val reply = ReviewReply.parse("""{"id":"C1.1","summary":"${"x".repeat(50_000)}"}""")!!

        assertTrue(reply.summary.length <= 4_000)
    }

    @Test
    fun `the round is read when the model bothers to include it`() {
        val reply = ReviewReply.parse("""{"id":"C1.1","summary":"x","round":"20260812-104233-a1b2"}""")!!

        assertEquals("20260812-104233-a1b2", reply.roundId)
    }
}
