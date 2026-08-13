package dev.andy.claudesessions.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAwareAction
import javax.swing.Icon

/**
 * The `+` beside a line number.
 *
 * Only one of these exists per editor at a time — the one on the line under the pointer. The
 * platform's own review renderer marks every commentable line with an invisible icon instead,
 * which costs a markup entry per line and is wasted when at most one can ever be seen.
 *
 * `equals` and `hashCode` are abstract on the base class and have to be honoured: the gutter
 * keeps renderers in hash-based collections and compares them to decide whether to repaint.
 * Identity-only equality produces a flickering icon and duplicates on the same line. Comparing
 * the line alone matches the platform's `LineGutterIconRenderer`, and the click handler is
 * deliberately left out — two renderers for the same line are the same icon.
 */
internal class ReviewGutterIcon(
    private val line: Int,
    private val onClick: (Int) -> Unit,
) : GutterIconRenderer() {

    override fun getIcon(): Icon = AllIcons.General.InlineAdd

    override fun getTooltipText(): String = TOOLTIP

    /**
     * Beside the line number, which is where the feature was asked for and also the one part of
     * the gutter nothing else claims: the icons area belongs to breakpoints and inspections, and
     * the free painters area to the VCS change stripes the diff itself draws.
     */
    override fun getAlignment(): Alignment = Alignment.LINE_NUMBERS

    /** So a single left click opens the note, rather than needing the popup menu. */
    override fun isNavigateAction(): Boolean = true

    override fun getClickAction(): AnAction = DumbAwareAction.create { onClick(line) }

    override fun equals(other: Any?): Boolean = other is ReviewGutterIcon && other.line == line

    override fun hashCode(): Int = line

    private companion object {
        const val TOOLTIP = "Write a note for Claude"
    }
}
