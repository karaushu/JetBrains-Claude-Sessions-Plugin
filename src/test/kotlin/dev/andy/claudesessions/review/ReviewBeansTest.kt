package dev.andy.claudesessions.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewBeansTest {

    private val now = 1_700_000_000_000

    private fun thread(
        id: String,
        status: ReviewThreadStatus = ReviewThreadStatus.OPEN,
        createdAtMillis: Long = now,
    ) = ReviewThread(
        id = id,
        anchor = ReviewAnchor(
            path = "src/api/client.ts",
            line = 141,
            lineText = "const res = await fetch(url)",
            contextLines = listOf("  // TODO", "", "  const res = await fetch(url)"),
            contextStartLine = 139,
            languageId = "ts",
        ),
        status = status,
        comments = listOf(
            ReviewComment("$id.1", CommentAuthor.USER, "No timeout here.", now),
            ReviewComment(
                id = "$id.1",
                author = CommentAuthor.AGENT,
                text = "Added a 5s timeout.\nRethrows as RequestTimeoutError.",
                createdAtMillis = now + 1,
                outcome = ReplyOutcome.DONE,
                touchedFiles = listOf("src/api/client.ts"),
            ),
        ),
        createdAtMillis = createdAtMillis,
        roundId = "20260812-104233-a1b2",
        sentToSessionId = "sess-9",
        sentAtMillis = now - 5,
        awaitingReplyTo = null,
        anchorLost = false,
        problem = ProtocolProblem.LATE_REPLY,
    )

    @Test
    fun `a thread survives the trip through the bean layer unchanged`() {
        val round = ReviewRound("20260812-104233-a1b2", "sess-9", now - 10, repliesOffset = 4_096)
        val saved = ReviewBeans.save(listOf(thread("C7")), listOf(round), 8, "sess-9")

        val loaded = ReviewBeans.load(saved, now)

        assertEquals(thread("C7"), loaded.threads.single())
        assertEquals(round, loaded.rounds.single())
        assertEquals(8, loaded.nextThreadOrdinal)
        assertEquals("sess-9", loaded.lastTargetSessionId)
    }

    @Test
    fun `a multi-line agent reply keeps its line breaks`() {
        val saved = ReviewBeans.save(listOf(thread("C7")), emptyList(), 8, null)

        val text = ReviewBeans.load(saved, now).threads.single().comments.last().text

        assertTrue(text.contains('\n'), "a reply is a paragraph, not a title")
    }

    @Test
    fun `an unrecognised status loads as open instead of losing the component`() {
        val saved = ReviewBeans.save(listOf(thread("C7")), emptyList(), 8, null)
        saved.threads.single().status = "SOMETHING_A_LATER_VERSION_INVENTED"
        saved.threads.single().problem = "ALSO_UNKNOWN"

        val loaded = ReviewBeans.load(saved, now).threads.single()

        assertEquals(ReviewThreadStatus.OPEN, loaded.status)
        assertEquals(null, loaded.problem)
    }

    @Test
    fun `a thread with no id or no path is dropped rather than half-loaded`() {
        val saved = ReviewBeans.save(listOf(thread("C7"), thread("C8")), emptyList(), 9, null)
        saved.threads[0].id = ""
        saved.threads[1].path = ""

        assertTrue(ReviewBeans.load(saved, now).threads.isEmpty())
    }

    @Test
    fun `the id counter is repaired from the threads that loaded`() {
        val threads = listOf(thread("C7"), thread("C31"))

        assertEquals(32, ReviewBeans.repairOrdinal(persisted = 3, threads = threads))
        assertEquals(99, ReviewBeans.repairOrdinal(persisted = 99, threads = threads))
        assertEquals(1, ReviewBeans.repairOrdinal(persisted = 0, threads = emptyList()))
    }

    @Test
    fun `an old closed thread is pruned but an old open one is kept`() {
        val ancient = now - 30L * 24 * 60 * 60 * 1000
        val saved = ReviewBeans.save(
            threads = listOf(
                thread("C1", ReviewThreadStatus.CLOSED, ancient),
                thread("C2", ReviewThreadStatus.OPEN, ancient),
                thread("C3", ReviewThreadStatus.SENT, ancient),
            ),
            rounds = emptyList(),
            nextThreadOrdinal = 4,
            lastTargetSessionId = null,
        )

        val ids = ReviewBeans.load(saved, now).threads.map { it.id }

        assertEquals(listOf("C2", "C3"), ids, "an unanswered note is never thrown away")
    }

    @Test
    fun `a long-closed round stops being loaded`() {
        val stale = ReviewRound("old", "sess-1", now - 8L * 24 * 60 * 60 * 1000, closed = true)
        val live = ReviewRound("new", "sess-1", now - 1_000, closed = false)
        val saved = ReviewBeans.save(emptyList(), listOf(stale, live), 1, null)

        val rounds = ReviewBeans.load(saved, now).rounds

        assertEquals(listOf("new"), rounds.map { it.id })
        assertFalse(rounds.single().closed)
    }
}
