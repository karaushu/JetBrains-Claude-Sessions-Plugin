package dev.andy.claudesessions.review

import dev.andy.claudesessions.model.SessionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewTargetsTest {

    @Test
    fun `the most recently focused session comes first`() {
        val options = ReviewTargets.options(
            openSessionIds = setOf("a", "b", "c"),
            focusOrder = listOf("c", "a"),
            titles = mapOf("a" to "Auth", "b" to "Invoices", "c" to "Cart"),
            states = emptyMap(),
            chosen = null,
        )

        assertEquals(listOf("c", "a", "b"), options.map { it.sessionId })
    }

    @Test
    fun `a session whose tab is closed is never offered`() {
        val options = ReviewTargets.options(
            openSessionIds = setOf("a"),
            focusOrder = listOf("gone", "a"),
            titles = emptyMap(),
            states = emptyMap(),
            chosen = "gone",
        )

        assertEquals(listOf("a"), options.map { it.sessionId })
        assertTrue(options.none { it.isChosen })
    }

    @Test
    fun `busy state is carried per session so the picker can show it`() {
        val options = ReviewTargets.options(
            openSessionIds = setOf("a", "b"),
            focusOrder = listOf("a", "b"),
            titles = emptyMap(),
            states = mapOf("a" to SessionState.RUNNING),
            chosen = "b",
        )

        assertEquals(SessionState.RUNNING, options.first().state)
        assertNull(options.last().state)
        assertTrue(options.last().isChosen)
    }

    @Test
    fun `a session with no title is still named`() {
        val options = ReviewTargets.options(
            openSessionIds = setOf("a"),
            focusOrder = emptyList(),
            titles = mapOf("a" to "  "),
            states = emptyMap(),
            chosen = null,
        )

        assertEquals("Untitled session", options.single().title)
    }

    @Test
    fun `a model-generated title is flattened before it reaches a button`() {
        assertEquals("one two", ReviewTargets.title("one\ntwo"))
    }

    @Test
    fun `the user's own choice wins while its tab is open`() {
        val target = ReviewTargets.resolve(setOf("a", "b"), listOf("b"), chosen = "a")

        assertEquals("a", target)
    }

    @Test
    fun `a stale choice falls back to the last focused session`() {
        val target = ReviewTargets.resolve(setOf("a", "b"), listOf("b", "a"), chosen = "gone")

        assertEquals("b", target)
    }

    @Test
    fun `with one session and no history that session is the target`() {
        assertEquals("a", ReviewTargets.resolve(setOf("a"), emptyList(), chosen = null))
    }

    @Test
    fun `with several sessions and no history nothing is assumed`() {
        assertNull(ReviewTargets.resolve(setOf("a", "b"), emptyList(), chosen = null))
    }

    @Test
    fun `with nothing open there is no target`() {
        assertNull(ReviewTargets.resolve(emptySet(), listOf("a"), chosen = "a"))
    }
}
