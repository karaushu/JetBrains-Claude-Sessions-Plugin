package dev.andy.claudesessions.review

import com.intellij.ide.ui.laf.darcula.DarculaUIUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

/**
 * Wraps the note field so it looks like every other multi-line field in the IDE.
 *
 * The text area alone was an opaque rectangle stretched to the full width of the block, which read
 * as a white slab rather than a field — and, because it reached right up to the buttons, put an
 * I-beam cursor where the pointer should have been an arrow.
 *
 * The rounded fill and the focus ring are painted here rather than borrowed from the platform's
 * text-field border: that border is applied by the text-field UI delegate and does not carry over
 * to a `JTextArea`. The arc and the colours still come from the theme, so a custom theme is
 * followed rather than second-guessed.
 */
internal class ReviewTextField(val area: JBTextArea) : JPanel(BorderLayout()) {

    init {
        isOpaque = false
        // The text area is laid out inside this padding, so the padding has to be wide enough to
        // hold the outline and the focus ring. It is not merely spacing: a child that reached the
        // outline would paint its own background straight over it, which is exactly how both the
        // grey edge and the blue focus ring came to be invisible.
        border = JBUI.Borders.empty(DarculaUIUtil.BW.get() + JBUI.scale(FOCUS_THICKNESS))
        area.isOpaque = false
        area.border = JBUI.Borders.empty(4, 7)
        add(area, BorderLayout.CENTER)

        val repaintOnFocus = object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = repaint()
            override fun focusLost(e: FocusEvent) = repaint()
        }
        area.addFocusListener(repaintOnFocus)
    }

    /** The field's own fill, under the text. */
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            g2.color = UIUtil.getTextFieldBackground()
            g2.fill(shape(0f))
        } finally {
            g2.dispose()
        }
    }

    /**
     * The outline, drawn after the children rather than before them.
     *
     * Everything painted in `paintComponent` goes underneath the text area, and a text area paints
     * its own background across the whole space it is given — which swallowed the outline whole and
     * left a white field with no edge at all. Drawing it here, on top, is the one arrangement no
     * child can undo. It is a hairline normally and [FOCUS_THICKNESS] when focused, in the theme's
     * focus colour; not [DarculaUIUtil.paintFocusBorder], whose outward fade read as too heavy
     * inside a diff.
     */
    override fun paint(g: Graphics) {
        super.paint(g)
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val focused = area.hasFocus()
            val thickness =
                if (focused) JBUI.scale(FOCUS_THICKNESS).toFloat()
                else DarculaUIUtil.LW.get().coerceAtLeast(1).toFloat()
            g2.color = if (focused) FOCUS_BORDER else NORMAL_BORDER
            g2.stroke = BasicStroke(thickness)
            // Half the stroke sits inside the path, so the path is inset by half of it and the
            // whole line lands on the component rather than half of it bleeding off the edge.
            g2.draw(shape(thickness / 2))
        } finally {
            g2.dispose()
        }
    }

    private fun shape(inset: Float): RoundRectangle2D.Float {
        val bw = DarculaUIUtil.BW.get().toFloat() + inset
        val arc = DarculaUIUtil.COMPONENT_ARC.get().toFloat()
        return RoundRectangle2D.Float(bw, bw, width - 2 * bw, height - 2 * bw, arc, arc)
    }

    private companion object {
        /** A shade under the platform's own ring, which read as too heavy inside a diff. */
        const val FOCUS_THICKNESS = 2

        /**
         * Our own outline colour rather than `Component.borderColor`: that key came out all but
         * white in the theme this was tried in, so the field had no visible edge at all. This is
         * the colour the block around it is drawn with, which keeps the two consistent.
         */
        val NORMAL_BORDER: JBColor get() = ReviewColors.border

        /**
         * The feature's own blue, under our own theme key.
         *
         * `Component.focusedBorderColor` came out white in the theme this was tried in, exactly as
         * `Component.borderColor` did — so both edges of this field would vanish on a white fill.
         * A key nothing else defines means the fallback always applies, which is the point.
         */
        val FOCUS_BORDER: JBColor get() = ReviewColors.sent
    }
}
