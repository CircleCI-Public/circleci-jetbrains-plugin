package com.circleci.idea.job

import com.circleci.idea.run.formatElapsed
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.time.Duration
import javax.swing.JComponent
import javax.swing.ToolTipManager
import kotlin.math.roundToInt

/** One line on a [UsageChart]: samples taken every [interval]. */
data class ChartSeries(
    val name: String,
    val values: List<Double>,
    val interval: Duration,
)

/**
 * A line chart of usage over a job's run, one line per execution, drawn
 * against the resource class [ceiling] as the CLI's charts are. With no
 * ceiling it scales to the data.
 */
class UsageChart(
    private val series: List<ChartSeries>,
    private val ceiling: Double,
    private val format: (Double) -> String,
) : JComponent() {
    private val maxSeconds = series.maxOfOrNull { it.interval.seconds * it.values.size }?.coerceAtLeast(1) ?: 1L
    private val yMax =
        maxOf(
            ceiling,
            series.maxOfOrNull { it.values.maxOrNull() ?: 0.0 } ?: 0.0,
        ).takeIf { it > 0 } ?: 1.0

    init {
        ToolTipManager.sharedInstance().registerComponent(this)
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(PREFERRED_WIDTH), JBUI.scale(PREFERRED_HEIGHT))

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            val plot = plotArea()
            paintGrid(g, plot)
            paintCeiling(g, plot)
            series.forEachIndexed { index, line -> paintSeries(g, plot, line, COLORS[index % COLORS.size]) }
            if (series.size > 1) paintLegend(g, plot)
        } finally {
            g.dispose()
        }
    }

    /** The samples nearest the pointer, by time. */
    override fun getToolTipText(event: MouseEvent): String? {
        val plot = plotArea()
        if (event.x < plot.left || event.x > plot.right) return null
        val seconds = (event.x - plot.left).toDouble() / plot.width * maxSeconds
        val lines =
            series.mapNotNull { line ->
                val step = line.interval.seconds.takeIf { it > 0 } ?: return@mapNotNull null
                val index = (seconds / step).roundToInt().coerceIn(0, line.values.lastIndex)
                line.values.getOrNull(index)?.let { "${line.name}: ${format(it)}" }
            }
        if (lines.isEmpty()) return null
        return "<html>${formatElapsed(Duration.ofSeconds(seconds.toLong()))}<br/>${lines.joinToString("<br/>")}</html>"
    }

    private fun plotArea(): Plot {
        val metrics = getFontMetrics(UIUtil.getLabelFont(UIUtil.FontSize.SMALL))
        val labelWidth = (0..GRID_LINES).maxOf { metrics.stringWidth(format(yMax * it / GRID_LINES)) }
        val pad = JBUI.scale(PADDING)
        return Plot(
            left = labelWidth + pad * 2,
            top = metrics.height + pad,
            right = width - pad,
            bottom = height - metrics.height - pad,
            textHeight = metrics.height,
        )
    }

    private fun paintGrid(
        g: Graphics2D,
        plot: Plot,
    ) {
        val metrics = g.fontMetrics
        for (i in 0..GRID_LINES) {
            val y = plot.y(yMax * i / GRID_LINES, yMax)
            g.color = GRID_COLOR
            g.drawLine(plot.left, y, plot.right, y)
            g.color = UIUtil.getContextHelpForeground()
            val label = format(yMax * i / GRID_LINES)
            g.drawString(label, plot.left - metrics.stringWidth(label) - JBUI.scale(PADDING), y + metrics.ascent / 2)
        }
        for (i in 0..GRID_LINES) {
            val x = plot.left + plot.width * i / GRID_LINES
            val label = formatElapsed(Duration.ofSeconds(maxSeconds * i / GRID_LINES))
            val labelWidth = metrics.stringWidth(label)
            val labelX = (x - labelWidth / 2).coerceIn(plot.left, plot.right - labelWidth)
            g.drawString(label, labelX, plot.bottom + plot.textHeight)
        }
    }

    private fun paintCeiling(
        g: Graphics2D,
        plot: Plot,
    ) {
        if (ceiling <= 0) return
        val y = plot.y(ceiling, yMax)
        g.color = JBColor.RED
        g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(DASH, DASH), 0f)
        g.drawLine(plot.left, y, plot.right, y)
        g.drawString("limit ${format(ceiling)}", plot.left + JBUI.scale(PADDING), y - JBUI.scale(2))
        g.stroke = BasicStroke(1f)
    }

    private fun paintSeries(
        g: Graphics2D,
        plot: Plot,
        line: ChartSeries,
        color: JBColor,
    ) {
        if (line.values.isEmpty()) return
        val path = Path2D.Double()
        line.values.forEachIndexed { index, value ->
            val x = plot.left + plot.width * (line.interval.seconds * index).toDouble() / maxSeconds
            val y = plot.y(value, yMax).toDouble()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        g.color = color
        g.stroke = BasicStroke(JBUI.scale(LINE_WIDTH).toFloat())
        g.draw(path)
        g.stroke = BasicStroke(1f)
    }

    private fun paintLegend(
        g: Graphics2D,
        plot: Plot,
    ) {
        var x = plot.left
        val y = plot.top - JBUI.scale(PADDING)
        series.forEachIndexed { index, line ->
            g.color = COLORS[index % COLORS.size]
            g.fillRect(x, y - g.fontMetrics.ascent / 2, JBUI.scale(SWATCH), JBUI.scale(SWATCH))
            x += JBUI.scale(SWATCH + PADDING)
            g.color = UIUtil.getLabelForeground()
            g.drawString(line.name, x, y + g.fontMetrics.ascent / 2 - JBUI.scale(2))
            x += g.fontMetrics.stringWidth(line.name) + JBUI.scale(PADDING * 3)
        }
    }

    private data class Plot(val left: Int, val top: Int, val right: Int, val bottom: Int, val textHeight: Int) {
        val width: Int get() = (right - left).coerceAtLeast(1)

        fun y(
            value: Double,
            max: Double,
        ): Int = bottom - ((bottom - top) * (value / max)).roundToInt()
    }

    private companion object {
        const val PREFERRED_WIDTH = 480
        const val PREFERRED_HEIGHT = 180
        const val GRID_LINES = 4
        const val PADDING = 4
        const val LINE_WIDTH = 2
        const val SWATCH = 8
        const val DASH = 4f

        val GRID_COLOR = JBColor.namedColor("Borders.color", JBColor.LIGHT_GRAY)
        val COLORS =
            listOf(
                JBColor(0x3574F0, 0x548AF7),
                JBColor(0x5FB865, 0x57965C),
                JBColor(0xE08855, 0xC77D55),
                JBColor(0x955AE0, 0xA571E6),
                JBColor(0x3BA7B8, 0x2AACB8),
                JBColor(0xD4A72C, 0xD6AE58),
            )
    }
}
