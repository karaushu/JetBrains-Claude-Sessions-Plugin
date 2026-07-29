package dev.andy.claudesessions.usage

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.FontUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.time.Instant
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The usage dropdown, mirroring what the desktop app shows.
 *
 * Each window is two lines: label and reset time and percent share the first, and the bar
 * spans the full width beneath. Putting the reset time on its own line made the rows read
 * as unrelated columns.
 */
internal class UsagePanel(snapshot: UsageSnapshot?) : JPanel(VerticalLayout(JBUI.scale(12))) {

    private var refreshing = false
    private var problem: String? = null
    private var atFloor = false

    init {
        border = JBUI.Borders.empty(12, 14, 10, 14)
        rebuild(snapshot)
    }

    fun showRefreshing(snapshot: UsageSnapshot?) {
        refreshing = true
        problem = null
        rebuild(snapshot)
    }

    fun showResult(snapshot: UsageSnapshot?, problem: String?, atFloor: Boolean = false) {
        refreshing = false
        this.problem = problem
        this.atFloor = atFloor
        rebuild(snapshot)
    }

    private fun rebuild(snapshot: UsageSnapshot?, now: Instant = Instant.now()) {
        removeAll()

        add(JBLabel("Your usage limits").apply { font = JBFont.label().asBold() })

        if (snapshot == null || snapshot.limits.isEmpty()) {
            add(dimmed(if (refreshing) "Asking Claude…" else "No usage data cached yet."))
            problem?.let { add(dimmed("Could not refresh — $it")) }
        } else {
            val stale = snapshot.isStale(now)
            for (limit in snapshot.limits) {
                add(row(limit, now, dimmed = stale || limit.hasRolledOver(now)))
            }
            add(dimmed(if (refreshing) "Asking Claude…" else UsageFormat.asOf(snapshot.fetchedAt, now)))
            problem?.let { add(dimmed("Could not refresh — $it")) }
            if (atFloor && !refreshing) {
                add(dimmed("Claude refreshes these at most every 5 minutes."))
            }
            if (stale && !refreshing && problem == null) {
                add(dimmed("Claude only refreshes this while it is running."))
            }
        }

        revalidate()
        repaint()
    }

    private fun dimmed(text: String) = JBLabel(text).apply {
        font = FontUtil.minusOne(JBFont.label())
        foreground = NamedColorUtil.getInactiveTextColor()
    }

    private fun row(limit: UsageLimit, now: Instant, dimmed: Boolean): JComponent {
        val rolledOver = limit.hasRolledOver(now)

        val heading = JPanel(BorderLayout(JBUI.scale(12), 0)).apply { isOpaque = false }
        heading.add(JBLabel(limit.label), BorderLayout.WEST)

        val trailing = JPanel(BorderLayout(JBUI.scale(8), 0)).apply { isOpaque = false }
        trailing.add(
            JBLabel(if (rolledOver) "already reset" else UsageFormat.resetShort(limit.resetsAt, now)).apply {
                font = FontUtil.minusOne(JBFont.label())
                foreground = NamedColorUtil.getInactiveTextColor()
            },
            BorderLayout.WEST,
        )
        trailing.add(
            JBLabel(UsageFormat.percent(limit.percent)).apply {
                font = JBFont.label().asBold()
                foreground = if (dimmed) NamedColorUtil.getInactiveTextColor() else percentColor(limit.percent)
            },
            BorderLayout.EAST,
        )
        heading.add(trailing, BorderLayout.EAST)

        val cell = JPanel(VerticalLayout(JBUI.scale(4))).apply { isOpaque = false }
        cell.add(heading)
        cell.add(UsageBar(limit.percent, dimmed))
        cell.preferredSize = Dimension(JBUI.scale(360), cell.preferredSize.height)
        return cell
    }

    /** Neutral until it actually matters, then warn. */
    private fun percentColor(percent: Int): Color = when {
        percent >= 90 -> JBColor.namedColor("Label.errorForeground", JBColor(0xE55765, 0xDB5C5C))
        percent >= 75 -> JBColor.namedColor("Component.warningFocusColor", JBColor(0xE0A200, 0xD6AE58))
        else -> JBColor.namedColor("Label.foreground", JBColor.foreground())
    }

    /** A thin bar; JProgressBar brings chrome that does not suit a dropdown. */
    private class UsageBar(private val percent: Int, private val dimmed: Boolean) : JComponent() {

        init {
            preferredSize = Dimension(JBUI.scale(360), JBUI.scale(4))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = height

                g2.color = JBColor.namedColor("ProgressBar.trackColor", JBColor(0xE6E6E6, 0x3E4145))
                g2.fillRoundRect(0, 0, width, height, arc, arc)

                val filled = (width * percent.coerceIn(0, 100) / 100.0).toInt()
                if (filled > 0) {
                    g2.color = if (dimmed) {
                        NamedColorUtil.getInactiveTextColor()
                    } else {
                        JBColor.namedColor("ProgressBar.progressColor", JBColor(0x3574F0, 0x3574F0))
                    }
                    g2.fillRoundRect(0, 0, filled, height, arc, arc)
                }
            } finally {
                g2.dispose()
            }
        }
    }
}
