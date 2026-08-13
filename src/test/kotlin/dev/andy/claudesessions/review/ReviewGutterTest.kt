package dev.andy.claudesessions.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The rule behind the `+`, and the renderer's equality contract.
 *
 * Neither test constructs an editor — that needs a running application — so these cover the
 * decisions rather than the painting. `getIcon()` is deliberately never called: it would reach
 * `IconLoader` and want an IDE around it.
 */
class ReviewGutterTest {

    @Test
    fun `the plus follows the hovered line`() {
        assertEquals(0, ReviewGutterHover.plusLine(0, lineCount = 10, occupiedLines = emptySet()))
        assertEquals(7, ReviewGutterHover.plusLine(7, lineCount = 10, occupiedLines = emptySet()))
    }

    @Test
    fun `there is no plus past the end of the file`() {
        assertNull(ReviewGutterHover.plusLine(10, lineCount = 10, occupiedLines = emptySet()))
        assertNull(ReviewGutterHover.plusLine(-1, lineCount = 10, occupiedLines = emptySet()))
    }

    @Test
    fun `an empty file offers nothing to comment on`() {
        assertNull(ReviewGutterHover.plusLine(0, lineCount = 0, occupiedLines = emptySet()))
    }

    @Test
    fun `a line that already has a note is not offered another`() {
        assertNull(ReviewGutterHover.plusLine(4, lineCount = 10, occupiedLines = setOf(4)))
        assertEquals(5, ReviewGutterHover.plusLine(5, lineCount = 10, occupiedLines = setOf(4)))
    }

    @Test
    fun `two renderers for one line are the same icon`() {
        val first = ReviewGutterIcon(12) {}
        val second = ReviewGutterIcon(12) { error("a different handler entirely") }

        // The gutter keeps renderers in hash-based collections and compares them to decide
        // whether to repaint; identity-only equality flickers and duplicates the icon.
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `renderers for different lines are different icons`() {
        assertNotEquals(ReviewGutterIcon(12) {}, ReviewGutterIcon(13) {})
    }

    @Test
    fun `the plus sits beside the line number`() {
        assertEquals(
            com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment.LINE_NUMBERS,
            ReviewGutterIcon(1) {}.alignment,
        )
    }

    @Test
    fun `a single click opens the note`() {
        assertEquals(true, ReviewGutterIcon(1) {}.isNavigateAction)
    }

    @Test
    fun `the tooltip says what the icon does, and is not model text`() {
        assertEquals("Write a note for Claude", ReviewGutterIcon(1) {}.tooltipText)
    }
}
