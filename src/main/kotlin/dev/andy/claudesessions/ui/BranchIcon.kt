package dev.andy.claudesessions.ui

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.QuadCurve2D
import javax.swing.Icon
import kotlin.math.max

/**
 * A small git-branch glyph drawn in an exact colour.
 *
 * Drawn rather than tinted on purpose. `IconUtil.colorize` converts to HSB and multiplies
 * the requested brightness by each source pixel's brightness, so tinting a stock icon
 * always lands darker than the colour asked for — it can never match adjacent text. Vector
 * drawing also stays crisp at any scale.
 */
internal class BranchIcon(
    private val unscaledSize: Int,
    private val color: Color,
) : Icon {

    override fun getIconWidth(): Int = JBUI.scale(unscaledSize)

    override fun getIconHeight(): Int = JBUI.scale(unscaledSize)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g2.color = color

            val s = getIconWidth().toFloat()
            g2.stroke = BasicStroke(
                max(1f, s / 11f),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
            )

            fun px(fraction: Float) = x + s * fraction
            fun py(fraction: Float) = y + s * fraction

            // Trunk.
            g2.draw(Line2D.Float(px(0.32f), py(0.22f), px(0.32f), py(0.78f)))
            // Branch springing off the trunk toward the upper right.
            g2.draw(
                QuadCurve2D.Float(
                    px(0.32f), py(0.56f),
                    px(0.62f), py(0.56f),
                    px(0.70f), py(0.34f),
                ),
            )
            // Commit nodes at both ends of the trunk and at the branch tip.
            dot(g2, px(0.32f), py(0.22f), s)
            dot(g2, px(0.32f), py(0.78f), s)
            dot(g2, px(0.70f), py(0.28f), s)
        } finally {
            g2.dispose()
        }
    }

    private fun dot(g2: Graphics2D, cx: Float, cy: Float, s: Float) {
        val r = s * 0.135f
        g2.fill(Ellipse2D.Float(cx - r, cy - r, r * 2f, r * 2f))
    }
}
