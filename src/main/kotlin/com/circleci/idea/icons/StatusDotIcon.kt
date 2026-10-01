package com.circleci.idea.icons

import com.circleci.idea.run.RunStatus
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * A small filled dot in a status's colour, as the Pull Requests list marks
 * its rows: a lighter touch than the full status icon, for lists.
 */
class StatusDotIcon(private val color: Color) : Icon {
    override fun getIconWidth(): Int = JBUIScale.scale(SIZE)

    override fun getIconHeight(): Int = JBUIScale.scale(SIZE)

    override fun paintIcon(
        c: Component?,
        g: Graphics,
        x: Int,
        y: Int,
    ) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(x, y, iconWidth, iconHeight)
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private const val SIZE = 8

        private val BLUE = JBColor(0x3574F0, 0x548AF7)
        private val GREEN = JBColor(0x369650, 0x5FAD65)
        private val RED = JBColor(0xE55765, 0xDB5C5C)
        private val PURPLE = JBColor(0x955AE0, 0xA571E6)
        private val GREY = JBColor(0xA8ADBD, 0x6F737A)

        private val DOTS = mutableMapOf<RunStatus, StatusDotIcon>()

        /** Blue while running, green passed, red failed, purple on hold, grey otherwise. */
        fun of(status: RunStatus): Icon =
            DOTS.getOrPut(status) {
                StatusDotIcon(
                    when (status) {
                        RunStatus.RUNNING -> BLUE
                        RunStatus.SUCCESS -> GREEN
                        RunStatus.FAILED, RunStatus.FAILING, RunStatus.ERROR -> RED
                        RunStatus.ON_HOLD -> PURPLE
                        else -> GREY
                    },
                )
            }
    }
}
