package dev.andy.claudesessions.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewDraftsTest {

    @Test
    fun `a half-written note survives being asked for again`() {
        val drafts = ReviewDrafts()

        drafts.put("src/a.ts", 10, "this needs a ti")

        assertEquals("this needs a ti", drafts.get("src/a.ts", 10))
    }

    @Test
    fun `drafts on different files and lines do not collide`() {
        val drafts = ReviewDrafts()

        drafts.put("src/a.ts", 10, "one")
        drafts.put("src/a.ts", 11, "two")
        drafts.put("src/b.ts", 10, "three")

        assertEquals(listOf(10, 11), drafts.linesIn("src/a.ts"))
        assertEquals(listOf(10), drafts.linesIn("src/b.ts"))
        assertTrue(drafts.linesIn("src/c.ts").isEmpty())
    }

    @Test
    fun `emptying a draft forgets it rather than keeping an empty box`() {
        val drafts = ReviewDrafts()
        drafts.put("src/a.ts", 10, "something")

        drafts.put("src/a.ts", 10, "   ")

        assertNull(drafts.get("src/a.ts", 10))
        assertTrue(drafts.linesIn("src/a.ts").isEmpty())
    }

    @Test
    fun `a reply draft is keyed by thread, because the thread's line moves`() {
        val drafts = ReviewDrafts()

        drafts.putReply("C7", "still wrong")

        assertEquals("still wrong", drafts.reply("C7"))
        assertNull(drafts.reply("C8"))

        drafts.removeReply("C7")
        assertNull(drafts.reply("C7"))
    }

    @Test
    fun `a file whose path is a prefix of another is not confused with it`() {
        val drafts = ReviewDrafts()

        drafts.put("src/a.ts", 1, "one")
        drafts.put("src/a.ts.bak", 2, "two")

        assertEquals(listOf(1), drafts.linesIn("src/a.ts"))
    }
}
