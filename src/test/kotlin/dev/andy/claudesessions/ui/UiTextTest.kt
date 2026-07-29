package dev.andy.claudesessions.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiTextTest {

    @Test
    fun `collapses newlines and tabs onto one line`() {
        assertEquals("a b c", UiText.oneLine("a\n\tb\r\n  c"))
    }

    @Test
    fun `trims and handles blank input`() {
        assertEquals("", UiText.oneLine(null))
        assertEquals("", UiText.oneLine("   \n  "))
        assertEquals("hi", UiText.oneLine("  hi  "))
    }

    @Test
    fun `truncates with an ellipsis`() {
        val result = UiText.oneLine("x".repeat(50), max = 10)
        assertEquals(10, result.length)
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun `turns control characters into a single space`() {
        val nul = 0.toChar()
        val bell = 7.toChar()
        assertEquals("a b", UiText.oneLine("a" + nul + "b"))
        assertEquals("a b", UiText.oneLine("a" + nul + bell + "b"))
    }

    @Test
    fun `leaves html looking titles as literal text`() {
        // Model-generated titles are untrusted; they must never become markup.
        assertEquals("<html><b>bold</b> title", UiText.oneLine("<html><b>bold</b> title"))
    }
}
