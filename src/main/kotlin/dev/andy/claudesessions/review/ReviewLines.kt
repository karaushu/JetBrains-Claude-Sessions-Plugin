package dev.andy.claudesessions.review

import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.util.Side
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.EditorEx

/**
 * Translates between the two line numberings a review has to hold at once.
 *
 * A note belongs to a line of the *file*, because that is what the agent edits and what the note
 * has to survive being re-anchored against. A panel is placed on a line of the *editor's document*,
 * which in a side-by-side diff is the same thing and in a unified diff is not: there one document
 * interleaves both sides, so its line numbers are its own and a removed line has no file line at
 * all.
 *
 * Keeping the conversion behind this interface is what lets one session implementation serve both,
 * instead of a unified-diff copy of everything that could drift from it.
 */
internal interface ReviewLines {

    /** Lines in the editor's document — the coordinates a panel or a gutter icon is placed at. */
    val documentLineCount: Int

    /** The file line shown at [documentLine], or null when that line is not in the file. */
    fun toFileLine(documentLine: Int): Int?

    /** Where [fileLine] is drawn, or null when this view does not show it. */
    fun toDocumentLine(fileLine: Int): Int?

    /** The file as it stands, for capturing context and for re-anchoring. */
    fun fileLines(): List<String>
}

/**
 * The straightforward case: the editor shows the file, so the two numberings are the same one.
 */
internal class PlainLines(private val editor: EditorEx) : ReviewLines {

    override val documentLineCount: Int get() = editor.document.lineCount

    override fun toFileLine(documentLine: Int): Int? = documentLine.takeIf { it in range() }

    override fun toDocumentLine(fileLine: Int): Int? = fileLine.takeIf { it in range() }

    override fun fileLines(): List<String> = editor.document.charsSequence.toString().split('\n')

    private fun range() = 0 until editor.document.lineCount
}

/**
 * The unified case, where the mapping comes from the viewer itself.
 *
 * Only the strict conversions are used. The lenient ones return the nearest line when there is no
 * exact match, which would put a note against code the user never pointed at — on a removed line,
 * say, which exists in the left side only. Refusing to place it is the honest answer, and it is
 * what stops the `+` appearing on a line no note could belong to.
 */
internal class UnifiedLines(private val viewer: UnifiedDiffViewer) : ReviewLines {

    override val documentLineCount: Int get() = viewer.editor.document.lineCount

    override fun toFileLine(documentLine: Int): Int? =
        viewer.transferLineFromOnesideStrict(Side.RIGHT, documentLine).takeIf { it >= 0 }

    override fun toDocumentLine(fileLine: Int): Int? =
        viewer.transferLineToOnesideStrict(Side.RIGHT, fileLine).takeIf { it >= 0 }

    override fun fileLines(): List<String> =
        document().charsSequence.toString().split('\n')

    private fun document(): Document = viewer.getDocument(Side.RIGHT)
}
