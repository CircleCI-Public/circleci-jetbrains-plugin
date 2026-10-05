package com.circleci.idea.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.circleci.idea.run.RunStatus
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import org.jetbrains.jewel.bridge.toComposeColor
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import javax.swing.Icon
import kotlin.math.sqrt

/*
 * A status's colour and shape follow `circleci`'s status glyphs. On hold,
 * which `circleci` doesn't show, is purple, as the web app has it.
 */

/** How a status's mark is drawn. */
private enum class StatusShape {
    /** A filled dot (●). */
    DOT,

    /** An empty ring (○). */
    RING,

    /** A ring with a slash through it (⊘). */
    SLASHED_RING,
}

/** The colour a status's mark is drawn in. */
fun statusColor(status: RunStatus): JBColor =
    when (status) {
        RunStatus.SUCCESS -> GREEN
        RunStatus.FAILING, RunStatus.FAILED -> RED
        RunStatus.ERRORING, RunStatus.ERROR, RunStatus.INFRASTRUCTURE_FAIL, RunStatus.TIMED_OUT -> YELLOW
        RunStatus.RUNNING -> BLUE
        RunStatus.ON_HOLD -> PURPLE
        RunStatus.CREATED,
        RunStatus.QUEUED,
        RunStatus.CANCELING,
        RunStatus.CANCELED,
        RunStatus.NOT_RUN,
        RunStatus.UNAUTHORIZED,
        RunStatus.UNKNOWN,
        -> GREY
    }

/** The shape a status's mark is drawn as. */
private fun statusShape(status: RunStatus): StatusShape =
    when (status) {
        RunStatus.CREATED, RunStatus.QUEUED -> StatusShape.RING
        RunStatus.CANCELING, RunStatus.CANCELED, RunStatus.NOT_RUN, RunStatus.UNAUTHORIZED -> StatusShape.SLASHED_RING
        RunStatus.SUCCESS,
        RunStatus.FAILING,
        RunStatus.FAILED,
        RunStatus.ERRORING,
        RunStatus.ERROR,
        RunStatus.INFRASTRUCTURE_FAIL,
        RunStatus.TIMED_OUT,
        RunStatus.RUNNING,
        RunStatus.ON_HOLD,
        RunStatus.UNKNOWN,
        -> StatusShape.DOT
    }

/** A status's mark, read out as [description]. */
@Composable
fun StatusDot(
    status: RunStatus,
    modifier: Modifier = Modifier,
    description: String = status.label,
) {
    val color = statusColor(status).toComposeColor()
    val shape = statusShape(status)
    Canvas(modifier.semantics { contentDescription = description }.size(DOT.dp)) {
        val stroke = STROKE.dp.toPx()
        val radius = (size.minDimension - stroke) / 2
        when (shape) {
            StatusShape.DOT -> drawCircle(color)
            StatusShape.RING -> drawCircle(color, radius, style = Stroke(stroke))
            StatusShape.SLASHED_RING -> {
                drawCircle(color, radius, style = Stroke(stroke))
                val d = radius / sqrt(2f)
                drawLine(color, center + Offset(-d, d), center + Offset(d, -d), stroke)
            }
        }
    }
}

/** A status's mark as a Swing icon. */
fun statusIcon(status: RunStatus): Icon = StatusDotIcon(statusColor(status), statusShape(status))

/** The mark centred in an icon-sized square, so it lines up with other icons. */
private class StatusDotIcon(
    private val color: JBColor,
    private val shape: StatusShape,
) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(ICON)

    override fun getIconHeight(): Int = JBUI.scale(ICON)

    override fun paintIcon(
        c: Component?,
        g: Graphics,
        x: Int,
        y: Int,
    ) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g2.color = color
            val dot = JBUI.scale(DOT).toFloat()
            val stroke = JBUI.scale(STROKE)
            val cx = x + iconWidth / 2f
            val cy = y + iconHeight / 2f
            if (shape == StatusShape.DOT) {
                g2.fill(Ellipse2D.Float(cx - dot / 2, cy - dot / 2, dot, dot))
                return
            }
            val radius = (dot - stroke) / 2
            g2.stroke = BasicStroke(stroke)
            g2.draw(Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2))
            if (shape == StatusShape.SLASHED_RING) {
                val d = radius / sqrt(2f)
                g2.draw(Line2D.Float(cx - d, cy + d, cx + d, cy - d))
            }
        } finally {
            g2.dispose()
        }
    }
}

private const val DOT = 8
private const val STROKE = 1.5f
private const val ICON = 16

private val GREEN = JBColor(0x369650, 0x5FAD65)
private val RED = JBColor(0xE55765, 0xDB5C5C)
private val YELLOW = JBColor(0xD4A72C, 0xD6AE58)
private val BLUE = JBColor(0x3574F0, 0x548AF7)
private val PURPLE = JBColor(0x955AE0, 0xA571E6)
private val GREY = JBColor(0xA8ADBD, 0x6F737A)
