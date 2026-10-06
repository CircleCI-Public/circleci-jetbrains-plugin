package com.circleci.idea.icons

import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import javax.swing.Icon

/**
 * [icon] with a dot in [color] at its top right, set off from it by a ring
 * of the background, as the IDE badges its own icons; its proportions are
 * the IDE's, for a 20-pixel icon.
 */
class BadgedIcon(private val icon: Icon, private val color: Color) : Icon {
    override fun getIconWidth(): Int = icon.iconWidth

    override fun getIconHeight(): Int = icon.iconHeight

    override fun paintIcon(
        c: Component?,
        g: Graphics,
        x: Int,
        y: Int,
    ) {
        icon.paintIcon(c, g, x, y)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val size = minOf(iconWidth, iconHeight).toDouble()
            val cx = x + size * DOT_X
            val cy = y + size * DOT_Y
            val radius = size * DOT_RADIUS
            val ring = radius + size * BORDER
            g2.color = c?.background ?: UIUtil.getPanelBackground()
            g2.fill(Ellipse2D.Double(cx - ring, cy - ring, ring * 2, ring * 2))
            g2.color = color
            g2.fill(Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2))
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val DOT_X = 16.5 / 20
        const val DOT_Y = 3.5 / 20
        const val DOT_RADIUS = 3.5 / 20
        const val BORDER = 1.5 / 20
    }
}
