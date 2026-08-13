package dev.andy.claudesessions.review

/**
 * Finds where a note's line ended up after the file changed.
 *
 * The agent edits the file while notes are open, so a stored line number is a hint, not an
 * address. The stored text of the line is the real identity. Everything here is pure and takes
 * the file's current lines as a list, which is what makes the awkward cases — a reformat, two
 * identical lines, a deletion — cheap to assert in a test.
 *
 * Drawing a note confidently on the wrong line is worse than admitting the anchor was lost, so
 * the search is bounded and gives up rather than widening indefinitely.
 */
internal object ReviewAnchoring {

    /**
     * Returns the 0-based line [lineText] now sits on, or null when it is no longer there.
     *
     * [taken] holds lines already claimed by other notes in the same pass, which stops two
     * threads from converging onto one line when the code between them was deleted.
     */
    fun anchor(
        lines: List<String>,
        storedLine: Int,
        lineText: String,
        taken: Set<Int> = emptySet(),
        window: Int = WINDOW_LINES,
    ): Int? {
        if (lines.isEmpty()) return null

        // A blank line carries no identity; searching for it would match dozens of candidates.
        if (lineText.isBlank()) {
            return storedLine.takeIf { it in lines.indices && it !in taken }
        }

        exact(lines, storedLine, lineText, taken, window)?.let { return it }
        return normalised(lines, storedLine, lineText, taken, window)
    }

    /** Resolves a whole file's notes in one pass, keeping them off each other's lines. */
    fun anchorAll(lines: List<String>, threads: List<ReviewThread>): Map<String, Int?> {
        val taken = mutableSetOf<Int>()
        val result = LinkedHashMap<String, Int?>(threads.size)
        for (thread in threads.sortedBy { it.anchor.line }) {
            val line = anchor(lines, thread.anchor.line, thread.anchor.lineText, taken)
            if (line != null) taken += line
            result[thread.id] = line
        }
        return result
    }

    private fun exact(
        lines: List<String>,
        storedLine: Int,
        lineText: String,
        taken: Set<Int>,
        window: Int,
    ): Int? = search(lines, storedLine, taken, window) { it.trim() == lineText }

    /**
     * Second pass, comparing with internal whitespace collapsed, so a note survives a
     * reformatter that only changed indentation or how the line was broken.
     */
    private fun normalised(
        lines: List<String>,
        storedLine: Int,
        lineText: String,
        taken: Set<Int>,
        window: Int,
    ): Int? {
        val wanted = collapse(lineText)
        if (wanted.isEmpty()) return null
        return search(lines, storedLine, taken, window) { collapse(it) == wanted }
    }

    /**
     * Walks outward from the stored line, checking below before above at each distance.
     *
     * Below first because inserting code above an anchor is far more common than deleting it,
     * so the line has usually moved to a higher index. Nearest always wins over farther.
     */
    private fun search(
        lines: List<String>,
        storedLine: Int,
        taken: Set<Int>,
        window: Int,
        matches: (String) -> Boolean,
    ): Int? {
        candidate(lines, storedLine, taken, matches)?.let { return it }
        for (distance in 1..window) {
            candidate(lines, storedLine + distance, taken, matches)?.let { return it }
            candidate(lines, storedLine - distance, taken, matches)?.let { return it }
        }
        return null
    }

    private fun candidate(
        lines: List<String>,
        line: Int,
        taken: Set<Int>,
        matches: (String) -> Boolean,
    ): Int? = line.takeIf { it in lines.indices && it !in taken && matches(lines[it]) }

    private fun collapse(line: String): String =
        line.trim().replace(WHITESPACE_RUN, " ")

    private val WHITESPACE_RUN = Regex("\\s+")

    private const val WINDOW_LINES = 40
}
