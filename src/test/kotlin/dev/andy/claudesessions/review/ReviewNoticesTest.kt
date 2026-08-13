package dev.andy.claudesessions.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewNoticesTest {

    @Test
    fun `a round the agent answered in full says nothing`() {
        assertNull(
            ReviewNotices.forRound(
                answered = 3,
                unanswered = 0,
                unknownIds = emptyList(),
                sessionTitle = "Auth",
                sessionGone = false,
            ),
        )
    }

    @Test
    fun `unanswered notes are reported with what did come back`() {
        val notice = ReviewNotices.forRound(2, 3, emptyList(), "Auth", sessionGone = false)!!

        assertEquals("no-reply", notice.kind)
        assertEquals("Claude finished without answering 3 comments", notice.title)
        assertTrue(notice.body.startsWith("2 comments were answered."), notice.body)
        assertTrue(notice.body.contains("in the diff"), "the code may well be fixed regardless")
    }

    @Test
    fun `one unanswered note reads as one`() {
        val notice = ReviewNotices.forRound(1, 1, emptyList(), "Auth", sessionGone = false)!!

        assertEquals("Claude finished without answering 1 comment", notice.title)
        assertTrue(notice.body.startsWith("1 comment was answered."), notice.body)
    }

    @Test
    fun `a session that ended is named as the reason`() {
        val notice = ReviewNotices.forRound(0, 2, emptyList(), "Auth", sessionGone = true)!!

        assertEquals("session-gone", notice.kind)
        assertEquals("Auth ended with 2 comments unanswered", notice.title)
        assertTrue(notice.body.startsWith("The code may still"), notice.body)
    }

    @Test
    fun `a reply about an id from nowhere is reported once, naming it`() {
        val notice = ReviewNotices.forRound(2, 0, listOf("C4.9"), "Auth", sessionGone = false)!!

        assertEquals("unknown-id", notice.kind)
        assertTrue(notice.title.contains("1 comment not in this review"), notice.title)
        assertTrue(notice.body.contains("C4.9"), notice.body)
    }

    @Test
    fun `unanswered notes matter more than an unrecognised id`() {
        val notice = ReviewNotices.forRound(0, 1, listOf("C4.9"), "Auth", sessionGone = false)!!

        assertEquals("no-reply", notice.kind)
    }

    @Test
    fun `a flood of unknown ids does not become a wall of text`() {
        val ids = (1..40).map { "C$it.1" }
        val notice = ReviewNotices.forRound(0, 0, ids, "Auth", sessionGone = false)!!

        assertTrue(notice.body.length < 200, notice.body)
    }
}
