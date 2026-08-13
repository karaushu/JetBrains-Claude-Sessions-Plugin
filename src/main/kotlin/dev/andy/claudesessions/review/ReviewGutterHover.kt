package dev.andy.claudesessions.review

/**
 * Where the `+` belongs for a given pointer position.
 *
 * Separated from the editor plumbing so the rule can be asserted without an IDE: the icon is
 * only ever on the hovered line, never past the end of the file, and never on a line that
 * already carries a note or an open box — there it would offer to do what has already been done.
 */
internal object ReviewGutterHover {

    /** The line the `+` should sit on, or null when it should not be shown at all. */
    fun plusLine(hoveredLine: Int, lineCount: Int, occupiedLines: Set<Int>): Int? = when {
        hoveredLine < 0 || hoveredLine >= lineCount -> null
        hoveredLine in occupiedLines -> null
        else -> hoveredLine
    }
}
