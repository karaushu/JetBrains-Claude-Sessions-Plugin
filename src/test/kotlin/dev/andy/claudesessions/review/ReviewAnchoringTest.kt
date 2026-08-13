package dev.andy.claudesessions.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReviewAnchoringTest {

    private val lines = listOf(
        "function send(url) {",
        "  const res = fetch(url)",
        "  return res.json()",
        "}",
    )

    @Test
    fun `an untouched line is found where it was left`() {
        assertEquals(1, ReviewAnchoring.anchor(lines, 1, "const res = fetch(url)"))
    }

    @Test
    fun `a line pushed down by an insertion above it is followed`() {
        val edited = listOf("import x from 'x'", "") + lines

        assertEquals(3, ReviewAnchoring.anchor(edited, 1, "const res = fetch(url)"))
    }

    @Test
    fun `a line pulled up by a deletion above it is followed`() {
        val edited = lines.drop(1)

        assertEquals(0, ReviewAnchoring.anchor(edited, 1, "const res = fetch(url)"))
    }

    @Test
    fun `a reformatted line still matches once whitespace is collapsed`() {
        val edited = listOf("function send(url) {", "\tconst   res =  fetch(url)")

        assertEquals(1, ReviewAnchoring.anchor(edited, 1, "const res = fetch(url)"))
    }

    @Test
    fun `the nearest of two identical lines wins`() {
        val edited = listOf("log()", "log()", "x", "log()")

        assertEquals(3, ReviewAnchoring.anchor(edited, 4, "log()"))
        assertEquals(0, ReviewAnchoring.anchor(edited, 0, "log()"))
    }

    @Test
    fun `a line below is preferred over an equally distant line above`() {
        val edited = listOf("log()", "x", "log()")

        assertEquals(2, ReviewAnchoring.anchor(edited, 1, "log()"))
    }

    @Test
    fun `a line that is gone reports nothing rather than guessing`() {
        assertNull(ReviewAnchoring.anchor(lines, 1, "const res = await fetchWithTimeout(url)"))
    }

    @Test
    fun `the search never leaves its window`() {
        val edited = List(200) { "filler" } + "const res = fetch(url)"

        assertNull(ReviewAnchoring.anchor(edited, 0, "const res = fetch(url)", window = 40))
        assertEquals(200, ReviewAnchoring.anchor(edited, 0, "const res = fetch(url)", window = 250))
    }

    @Test
    fun `a blank anchor stays where it was because it identifies nothing`() {
        val blank = listOf("a", "", "b")

        assertEquals(1, ReviewAnchoring.anchor(blank, 1, ""))
        assertNull(ReviewAnchoring.anchor(blank, 9, ""), "and does not survive being out of range")
    }

    @Test
    fun `two notes cannot converge onto the same line`() {
        val edited = listOf("log()", "log()")
        val threads = listOf(threadAt(0, "log()"), threadAt(1, "log()"))

        val result = ReviewAnchoring.anchorAll(edited, threads)

        assertEquals(mapOf("t0" to 0, "t1" to 1), result)
    }

    @Test
    fun `the second of two notes on a vanished duplicate is reported lost`() {
        val edited = listOf("log()")
        val threads = listOf(threadAt(0, "log()"), threadAt(1, "log()"))

        val result = ReviewAnchoring.anchorAll(edited, threads)

        assertEquals(0, result["t0"])
        assertNull(result["t1"], "the line it pointed at really is not there any more")
    }

    private fun threadAt(line: Int, text: String) = ReviewThread(
        id = "t$line",
        anchor = ReviewAnchor(path = "a.ts", line = line, lineText = text),
        status = ReviewThreadStatus.OPEN,
        comments = emptyList(),
        createdAtMillis = 0,
    )
}
