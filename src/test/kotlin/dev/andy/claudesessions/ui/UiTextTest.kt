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

    @Test
    fun `multiLine keeps the paragraph breaks a reply needs`() {
        assertEquals("first line\n\nsecond line", UiText.multiLine("first line\n\nsecond line"))
    }

    @Test
    fun `multiLine collapses a gap the model left behind`() {
        assertEquals("a\n\nb", UiText.multiLine("a\n\n\n\n\nb"))
    }

    @Test
    fun `multiLine normalises windows line endings`() {
        assertEquals("a\nb", UiText.multiLine("a\r\nb"))
    }

    @Test
    fun `multiLine strips control characters without eating the line breaks`() {
        val bell = 7.toChar()
        assertEquals("ab\ncd", UiText.multiLine("a" + bell + "b\ncd"))
    }

    @Test
    fun `multiLine caps the number of lines and says it did`() {
        val result = UiText.multiLine((1..100).joinToString("\n"), maxLines = 5)

        assertEquals(6, result.lines().size)
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun `multiLine caps the length`() {
        val result = UiText.multiLine("x".repeat(500), maxChars = 20)

        assertEquals(20, result.length)
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun `multiLine handles blank input`() {
        assertEquals("", UiText.multiLine(null))
        assertEquals("", UiText.multiLine("  \n\n  "))
    }
}
