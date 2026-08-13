package dev.andy.claudesessions.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The store needs no service container: it takes no constructor arguments and holds only flows,
 * so it can be exercised the same way `ClaudeSessionsSettingsTest` exercises the settings.
 */
class ReviewStoreTest {

    private fun anchor(line: Int, text: String = "const x = 1", path: String = "src/a.ts") =
        ReviewAnchor(path = path, line = line, lineText = text)

    private fun reply(id: String, text: String = "Fixed.") =
        ReviewReply(id, text, ReplyOutcome.DONE, emptyList(), null)

    @Test
    fun `a comment on a bare line starts a thread`() {
        val store = ReviewStore()

        val id = store.addComment(anchor(10), "no timeout here", 1_000)

        val thread = store.find(id)!!
        assertEquals(ReviewThreadStatus.OPEN, thread.status)
        assertEquals("$id.1", thread.comments.single().id)
        assertEquals(CommentAuthor.USER, thread.comments.single().author)
    }

    @Test
    fun `a second comment on the same line continues the same thread`() {
        val store = ReviewStore()

        val first = store.addComment(anchor(10), "one", 1_000)
        val second = store.addComment(anchor(10), "two", 2_000)

        assertEquals(first, second, "one conversation per line")
        assertEquals(listOf("C1.1", "C1.2"), store.find(first)!!.comments.map { it.id })
    }

    @Test
    fun `a comment on a closed line starts a fresh thread rather than waking the old one`() {
        val store = ReviewStore()
        val first = store.addComment(anchor(10), "one", 1_000)
        store.close(first)

        val second = store.addComment(anchor(10), "two", 2_000)

        assertNotEquals(first, second)
        assertEquals(2, store.threads.value.size)
    }

    @Test
    fun `ids keep counting up so a reply can never land in the wrong thread`() {
        val store = ReviewStore()

        val first = store.addComment(anchor(1), "a", 1_000)
        val second = store.addComment(anchor(2), "b", 1_000)
        store.delete(first)
        val third = store.addComment(anchor(3), "c", 1_000)

        assertEquals(listOf("C1", "C2", "C3"), listOf(first, second, third))
    }

    @Test
    fun `only open threads are sendable and sending marks exactly those`() {
        val store = ReviewStore()
        val open = store.addComment(anchor(1), "a", 1_000)
        val other = store.addComment(anchor(2), "b", 1_000)
        val round = ReviewRound("r1", "sess-1", 1_100)

        store.markSent(listOf(open), round, 1_100)

        assertEquals(listOf(other), store.sendableThreads().map { it.id })
        assertEquals("C1.1", store.find(open)!!.awaitingReplyTo)
        assertEquals(listOf("C1.1"), store.awaitedCommentIds("r1"))
        assertEquals(listOf("r1"), store.openRounds().map { it.id })
    }

    @Test
    fun `a failed send puts the threads back and forgets the round`() {
        val store = ReviewStore()
        val id = store.addComment(anchor(1), "a", 1_000)
        store.markSent(listOf(id), ReviewRound("r1", "sess-1", 1_100), 1_100)

        store.rollbackSend(listOf(id), "r1")

        assertEquals(listOf(id), store.sendableThreads().map { it.id })
        assertTrue(store.openRounds().isEmpty())
    }

    @Test
    fun `the awaited reply answers its thread`() {
        val store = ReviewStore()
        val id = store.addComment(anchor(1), "a", 1_000)
        store.markSent(listOf(id), ReviewRound("r1", "sess-1", 1_100), 1_100)

        val match = store.applyReply(reply("C1.1"), 1_200)

        assertEquals(ReplyMatch.Applied(id, late = false), match)
        assertEquals(ReviewThreadStatus.ANSWERED, store.find(id)!!.status)
        assertTrue(store.awaitedCommentIds("r1").isEmpty())
    }

    @Test
    fun `a reply that names the thread instead of the comment still lands`() {
        val store = ReviewStore()
        val id = store.addComment(anchor(1), "a", 1_000)
        store.markSent(listOf(id), ReviewRound("r1", "sess-1", 1_100), 1_100)

        val match = store.applyReply(reply("C1"), 1_200)

        assertEquals(ReplyMatch.Applied(id, late = false), match)
        assertEquals("C1.1", store.find(id)!!.comments.last().id)
    }

    @Test
    fun `applying the same reply twice adds one comment`() {
        val store = ReviewStore()
        val id = store.addComment(anchor(1), "a", 1_000)
        store.markSent(listOf(id), ReviewRound("r1", "sess-1", 1_100), 1_100)
        store.applyReply(reply("C1.1"), 1_200)

        val second = store.applyReply(reply("C1.1"), 1_300)

        assertEquals(ReplyMatch.Duplicate, second, "a replayed replies file must be harmless")
        assertEquals(2, store.find(id)!!.comments.size)
    }

    @Test
    fun `a reply to an id nobody recognises changes nothing`() {
        val store = ReviewStore()
        val id = store.addComment(anchor(1), "a", 1_000)
        store.markSent(listOf(id), ReviewRound("r1", "sess-1", 1_100), 1_100)

        val match = store.applyReply(reply("C9.9"), 1_200)

        assertEquals(ReplyMatch.Unknown("C9.9"), match)
        assertEquals(ReviewThreadStatus.SENT, store.find(id)!!.status)
        assertEquals(1, store.find(id)!!.comments.size)
    }

    @Test
    fun `clearing throws away every note but not the id counter`() {
        val store = ReviewStore()
        store.addComment(anchor(1), "a", 1_000)
        val second = store.addComment(anchor(2), "b", 1_000)
        store.markSent(listOf(second), ReviewRound("r1", "sess-1", 1_100), 1_100)

        store.clearAll()

        assertTrue(store.threads.value.isEmpty())
        assertTrue(store.openRounds().isEmpty())
        // A reply from the round that just left must not land on a note minted afterwards.
        assertEquals("C3", store.addComment(anchor(3), "c", 2_000))
    }

    @Test
    fun `threads are ordered by file and line for every consumer`() {
        val store = ReviewStore()
        store.addComment(anchor(20, path = "src/b.ts"), "b20", 1_000)
        store.addComment(anchor(5, path = "src/b.ts"), "b5", 1_000)
        store.addComment(anchor(9, path = "src/a.ts"), "a9", 1_000)

        val order = store.threads.value.map { "${it.anchor.path}:${it.anchor.line}" }

        assertEquals(listOf("src/a.ts:9", "src/b.ts:5", "src/b.ts:20"), order)
    }

    @Test
    fun `re-anchoring moves a thread and detaches the one whose line went away`() {
        val store = ReviewStore()
        val moved = store.addComment(anchor(10), "a", 1_000)
        val lost = store.addComment(anchor(20), "b", 1_000)

        store.reanchor(mapOf(moved to 14, lost to null))

        assertEquals(14, store.find(moved)!!.anchor.line)
        assertTrue(store.find(lost)!!.anchorLost)
        assertEquals(20, store.find(lost)!!.anchor.line, "the last known line is still shown")
    }

    @Test
    fun `threads for a file exclude the closed ones`() {
        val store = ReviewStore()
        val kept = store.addComment(anchor(1), "a", 1_000)
        val closed = store.addComment(anchor(2), "b", 1_000)
        store.close(closed)
        store.addComment(anchor(3, path = "src/other.ts"), "c", 1_000)

        assertEquals(listOf(kept), store.threadsFor("src/a.ts").map { it.id })
    }

    @Test
    fun `the chosen target survives a save and load`() {
        val store = ReviewStore()
        store.addComment(anchor(1), "a", 1_000)
        store.chooseTarget("sess-7")

        val reloaded = ReviewStore().apply { loadState(store.getState()) }

        assertEquals("sess-7", reloaded.targetSessionId.value)
        assertEquals(1, reloaded.threads.value.size)
        assertEquals("C2", reloaded.addComment(anchor(2), "b", 1_000), "the counter is not reset")
    }

    @Test
    fun `a round's read position is remembered so a restart resumes mid-file`() {
        val store = ReviewStore()
        val id = store.addComment(anchor(1), "a", 1_000)
        store.markSent(listOf(id), ReviewRound("r1", "sess-1", 1_100), 1_100)

        store.noteRepliesOffset("r1", 512)
        val reloaded = ReviewStore().apply { loadState(store.getState()) }

        assertEquals(512, reloaded.openRounds().single().repliesOffset)

        reloaded.closeRound("r1")
        assertTrue(reloaded.openRounds().isEmpty())
        assertNull(reloaded.find(id)!!.problem)
    }
}
