package dev.andy.claudesessions.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ExperimentalUI
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.popup.list.SelectablePanel
import com.intellij.ui.render.RenderingUtil
import com.intellij.util.FontUtil
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import dev.andy.claudesessions.model.SessionItem
import dev.andy.claudesessions.model.SessionState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer

/**
 * Renders both node kinds: a project heading, and a two-line session row.
 *
 * [SimpleColoredComponent] rather than `JLabel` on purpose — session titles are
 * model-generated and `JLabel` silently switches to HTML rendering for any string
 * starting with `<html>`. `SimpleColoredComponent` always renders plain text.
 */
internal class SessionTreeCellRenderer : SelectablePanel(), TreeCellRenderer {

    private val statusIcon = SimpleColoredComponent().apply {
        isOpaque = false
        ipad = JBInsets.emptyInsets()
    }

    private val title = SimpleColoredComponent().apply {
        isOpaque = false
        ipad = JBInsets.emptyInsets()
    }

    private val subtitle = SimpleColoredComponent().apply {
        isOpaque = false
        ipad = JBInsets.emptyInsets()
        font = FontUtil.minusOne(JBFont.label())
    }

    private val branchIcons = mutableMapOf<Color, Icon>()

    /** The row-actions affordance, revealed on hover. */
    private val moreActions = SimpleColoredComponent().apply {
        isOpaque = false
        ipad = JBInsets.emptyInsets()
    }

    /** Session id the mouse is currently over, set by the panel. */
    var hoveredSessionId: String? = null

    private val textPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(title, BorderLayout.CENTER)
        add(subtitle, BorderLayout.SOUTH)
    }

    init {
        layout = BorderLayout(JBUI.scale(4), 0)
        isOpaque = false
        add(statusIcon, BorderLayout.WEST)
        add(textPanel, BorderLayout.CENTER)
        add(moreActions, BorderLayout.EAST)
    }

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        // Stretch the row to the viewport so the trailing gutter lands at the right edge,
        // where the click is hit-tested. Indent is derived from the node's depth rather than
        // from getRowBounds, which would ask this renderer for its size and recurse.
        //
        // Measure the viewport, not the tree: the tree's own width grows to whatever the rows
        // ask for, so using it would ratchet wider each pass. Erring a couple of pixels short
        // matters because horizontal scrolling is disabled — overshooting would clip the
        // gutter and hide the button rather than produce a scrollbar.
        val depth = ((value as? DefaultMutableTreeNode)?.level ?: 1).coerceAtLeast(0)
        val available = (tree.parent as? JViewport)?.width?.takeIf { it > 0 }
            ?: tree.visibleRect.width.takeIf { it > 0 }
            ?: tree.width
        preferredWidth = (available - depth * indentPerLevel(tree) - JBUI.scale(SAFETY_MARGIN))
            .coerceAtLeast(JBUI.scale(120))

        val newUi = ExperimentalUI.isNewUI()
        border = JBUI.Borders.empty(3, if (newUi) 6 else 4, 4, 8)

        if (newUi) {
            selectionArc = JBUI.CurrentTheme.Popup.Selection.ARC.get()
            selectionArcCorners = SelectionArcCorners.ALL
            selectionInsets = JBInsets(0, JBUI.scale(4), 0, JBUI.scale(4))
        } else {
            selectionArc = 0
            selectionInsets = JBInsets.emptyInsets()
        }

        background = RenderingUtil.getBackground(tree, false)
        selectionColor = if (selected) RenderingUtil.getSelectionBackground(tree) else null
        val primary = RenderingUtil.getForeground(tree, selected)
        val secondary = if (selected) primary else NamedColorUtil.getInactiveTextColor()

        title.clear()
        subtitle.clear()
        statusIcon.clear()
        moreActions.clear()
        moreActions.icon = null

        when (val payload = (value as? DefaultMutableTreeNode)?.userObject) {
            is SessionItem -> renderSession(payload, primary, secondary)
            is ProjectGroup -> renderProject(payload, primary, secondary)
        }
        return this
    }

    private fun renderSession(item: SessionItem, primary: Color, secondary: Color) {
        textPanel.isVisible = true
        subtitle.isVisible = true
        statusIcon.icon = SessionStateIcons.of(item.state)
        title.append(
            UiText.oneLine(sessionTitle(item)),
            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, primary),
        )
        // Leading icon on the second line, so the branch name reads as a branch.
        // Drawn in the subtitle's exact colour; see BranchIcon.
        subtitle.icon = if (item.summary.gitBranch != null) branchIcon(secondary) else null
        subtitle.append(
            UiText.oneLine(sessionSubtitle(item)),
            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, secondary),
        )
        if (item.sessionId == hoveredSessionId) {
            moreActions.icon = AllIcons.Actions.More
        }
    }

    /** Project headings are a single bold line; the row is a heading, not data. */
    private fun renderProject(group: ProjectGroup, primary: Color, secondary: Color) {
        textPanel.isVisible = true
        subtitle.isVisible = false
        subtitle.icon = null
        title.append(
            UiText.oneLine(group.name),
            SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, primary),
        )
        val counts = buildString {
            append(group.sessionCount)
            if (group.liveCount > 0) append(", ${group.liveCount} live")
        }
        title.append("  $counts", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, secondary))
    }

    /**
     * The branch glyph in exactly the subtitle's colour.
     *
     * Cached because a renderer runs for every visible row on every repaint, and the
     * spinner keeps repaints coming several times a second. There are only ever a couple
     * of distinct colours (dimmed, and the selection foreground).
     */
    private fun branchIcon(color: Color): Icon = branchIcons.getOrPut(color) {
        BranchIcon(BRANCH_ICON_SIZE, color)
    }

    /**
     * Claude names a session only after its first message, so an unnamed one is described
     * by what it is rather than by an id the user has no use for.
     */
    private fun sessionTitle(item: SessionItem): String =
        item.summary.title ?: item.untitledDescription

    private fun sessionSubtitle(item: SessionItem): String {
        val parts = mutableListOf<String>()
        if (item.state == SessionState.NEEDS_INPUT) {
            parts += item.live?.waitingFor ?: "waiting for input"
        }
        if (item.isBackgroundAgent) parts += "background agent"
        // With no title, the id is the only way to tell two of these apart.
        if (item.summary.title == null) parts += item.sessionId.take(8)
        item.summary.gitBranch?.let { parts += it }  // icon supplied by renderSession
        // Only shown when the session is not in the main working tree.
        item.summary.worktreeName?.let { parts += "in $it" }
        parts += DateFormatUtil.formatPrettyDateTime(item.summary.lastActivity.toEpochMilli())
        return parts.joinToString("  ·  ")
    }

    /** Width of the trailing gutter, for deciding whether a click hit the dots. */
    fun moreActionsWidth(): Int = JBUI.scale(MORE_ACTIONS_WIDTH)

    private fun indentPerLevel(tree: JTree): Int {
        val ui = tree.ui as? javax.swing.plaf.basic.BasicTreeUI ?: return JBUI.scale(FALLBACK_INDENT)
        return (ui.leftChildIndent + ui.rightChildIndent).takeIf { it > 0 } ?: JBUI.scale(FALLBACK_INDENT)
    }

    private companion object {
        /** AllIcons.Actions.More is 16px; a little breathing room each side. */
        const val MORE_ACTIONS_WIDTH = 22

        const val FALLBACK_INDENT = 20

        /**
         * Kept clear of the viewport edge. Indent is derived from the tree UI rather than
         * measured, so it can be a little off; erring inward keeps the row-actions gutter
         * visible instead of clipped.
         */
        const val SAFETY_MARGIN = 8

        /** Unscaled; the platform applies HiDPI scaling itself. */
        const val BRANCH_ICON_SIZE = 12
    }
}
