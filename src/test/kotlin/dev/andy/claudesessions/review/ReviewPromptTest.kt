package dev.andy.claudesessions.review

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ReviewPromptTest {

    private val file: Path =
        Path.of("/Users/dev/.claude/claudesessions/reviews/-Users-dev-web/review-r1.md")

    @Test
    fun `it does not start with a slash, which the TUI would read as a command`() {
        assertFalse(ReviewPrompt.forRound(file, 3).startsWith("/"))
    }

    @Test
    fun `it is a single line, so nothing is left behind as a second prompt`() {
        assertFalse(ReviewPrompt.forRound(file, 3).contains('\n'))
        assertFalse(ReviewPrompt.forRound(file, 3).contains('\r'))
    }

    @Test
    fun `it contains no at sign, which would open the file-mention popup`() {
        assertFalse(ReviewPrompt.forRound(file, 3).contains('@'))
    }

    @Test
    fun `it names the file the agent has to read`() {
        assertTrue(ReviewPrompt.forRound(file, 3).contains(file.toString()))
    }

    @Test
    fun `a path with a space in it is quoted`() {
        val spaced = Path.of("/Users/my dev/.claude/claudesessions/reviews/x/review-r1.md")

        assertTrue(ReviewPrompt.forRound(spaced, 1).contains("'$spaced'"))
    }

    @Test
    fun `it counts the comments correctly`() {
        assertTrue(ReviewPrompt.forRound(file, 1).contains("all 1 comment,"))
        assertTrue(ReviewPrompt.forRound(file, 4).contains("all 4 comments,"))
    }

    @Test
    fun `it stays short enough to paste safely`() {
        assertTrue(ReviewPrompt.forRound(file, 12).length < 400)
    }
}
