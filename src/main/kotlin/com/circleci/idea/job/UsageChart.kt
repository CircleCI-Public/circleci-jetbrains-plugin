package com.circleci.idea.job

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intellij.ui.JBColor
import io.github.koalaplot.core.line.AreaBaseline
import io.github.koalaplot.core.line.AreaPlot
import io.github.koalaplot.core.line.LinePlot
import io.github.koalaplot.core.style.AreaStyle
import io.github.koalaplot.core.style.LineStyle
import io.github.koalaplot.core.xygraph.AnchorPoint
import io.github.koalaplot.core.xygraph.AxisStyle
import io.github.koalaplot.core.xygraph.GridStyle
import io.github.koalaplot.core.xygraph.HorizontalLineAnnotation
import io.github.koalaplot.core.xygraph.Point
import io.github.koalaplot.core.xygraph.VerticalLineAnnotation
import io.github.koalaplot.core.xygraph.XYAnnotation
import io.github.koalaplot.core.xygraph.XYGraph
import io.github.koalaplot.core.xygraph.XYGraphScope
import io.github.koalaplot.core.xygraph.rememberAxisContent
import io.github.koalaplot.core.xygraph.rememberAxisStyle
import io.github.koalaplot.core.xygraph.rememberDoubleLinearAxisModel
import org.jetbrains.jewel.bridge.retrieveColorOrUnspecified
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography
import kotlin.math.roundToInt

/** Where the pointer is over a chart: in data, for the crosshair, and in pixels, for the card. */
private data class Hover(val seconds: Double, val position: Offset)

/**
 * A line chart of usage over a job's run, one line per execution, drawn
 * against the resource class [ceiling] as the CLI's charts are. With no
 * ceiling it scales to the data. Hovering shows each execution's sample at
 * that point in the run.
 */
@Composable
internal fun UsageChart(
    series: List<ChartSeries>,
    ceiling: Double,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
) {
    val xMax = remember(series) { timeExtent(series) }
    val yMax = remember(series, ceiling) { valueExtent(series, ceiling) * HEADROOM }
    val colors = remember(series.size, JewelTheme.isDark) { series.indices.map { seriesColor(it) } }
    val points = remember(series) { series.map(::points) }
    val gridColor = JewelTheme.globalColors.borders.normal.copy(alpha = GRID_ALPHA)
    val labelStyle = JewelTheme.typography.small.copy(color = JewelTheme.globalColors.text.info)
    val xAxis = rememberDoubleLinearAxisModel(0.0..xMax, minimumMajorTickSpacing = X_TICK_SPACING.dp)
    val yAxis = rememberDoubleLinearAxisModel(0.0..yMax, minimumMajorTickSpacing = Y_TICK_SPACING.dp)
    val xLabels =
        rememberAxisContent<Double>(labels = { Text(formatSeconds(it), style = labelStyle) }, style = axisStyle(1.dp))
    val yLabels = rememberAxisContent<Double>(labels = { YLabel(format(it), labelStyle) }, style = axisStyle(0.dp))

    // Read only by the crosshair and the card, so moving the pointer recomposes just them.
    val hover = remember(series) { mutableStateOf<Hover?>(null) }
    val snapped = remember(series) { derivedStateOf { hover.value?.let { snap(series, it.seconds) } } }

    // The plot sees each event first, so this only moves a card it has shown.
    val moveCard =
        Modifier.pointerInput(series) {
            awaitPointerEventScope {
                while (true) {
                    val position = awaitPointerEvent().changes.firstOrNull()?.position ?: continue
                    hover.value = hover.value?.copy(position = position)
                }
            }
        }
    Box(modifier.fillMaxWidth().height(CHART_HEIGHT.dp).then(moveCard)) {
        XYGraph(
            xAxisModel = xAxis,
            yAxisModel = yAxis,
            xAxisContent = xLabels,
            yAxisContent = yLabels,
            gridStyle = GridStyle(LineStyle(SolidColor(gridColor), 1.dp), null, null, null),
            // Positions here are within the plot; the Box above moves the card in its own.
            onPointerEvent = { event ->
                val position = event.changes.firstOrNull()?.position
                val exited = event.type == PointerEventType.Exit || position == null
                hover.value = if (exited) null else Hover(scale(position).x, hover.value?.position ?: position)
            },
        ) {
            Lines(points, colors)
            if (ceiling > 0) Limit(ceiling, format(ceiling), labelStyle)
            Crosshair(series, colors, snapped)
        }
        HoverCard(hover, snapped) { seconds -> HoverContent(series, colors, seconds, ceiling, format) }
    }
    if (series.size > 1) Legend(series, colors)
}

@Composable
private fun axisStyle(lineWidth: Dp): AxisStyle =
    rememberAxisStyle(
        color = JewelTheme.globalColors.borders.normal.copy(alpha = GRID_ALPHA),
        majorTickSize = 0.dp,
        minorTickSize = 0.dp,
        lineWidth = lineWidth,
    )

@Composable
private fun YLabel(
    text: String,
    style: TextStyle,
) {
    Text(text, style = style, modifier = Modifier.padding(end = 4.dp))
}

/** One line per execution; a lone execution's is filled down to zero. */
@Composable
private fun XYGraphScope<Double, Double>.Lines(
    lines: List<List<Point<Double, Double>>>,
    colors: List<Color>,
) {
    val single = lines.size == 1
    lines.forEachIndexed { index, points ->
        if (points.isEmpty()) return@forEachIndexed
        val color = colors[index]
        val stroke = LineStyle(SolidColor(color), if (single) 2.dp else 1.5.dp)
        if (single) {
            val fill = AreaStyle(Brush.verticalGradient(listOf(color.copy(alpha = FILL_TOP), color.copy(alpha = 0f))))
            AreaPlot(
                data = points,
                areaBaseline = AreaBaseline.HorizontalLine(0.0),
                areaStyle = fill,
                lineStyle = stroke,
            )
        } else {
            LinePlot(data = points, lineStyle = stroke)
        }
    }
}

/** The resource class's limit, dashed, with its value. */
@Composable
private fun XYGraphScope<Double, Double>.Limit(
    ceiling: Double,
    label: String,
    style: TextStyle,
) {
    val color = JBColor.RED.toComposeColor()
    val dashed = LineStyle(SolidColor(color), 1.dp, PathEffect.dashPathEffect(floatArrayOf(DASH, DASH)))
    HorizontalLineAnnotation(ceiling, dashed)
    XYAnnotation(Point(0.0, ceiling), AnchorPoint.BottomLeft) {
        Text("limit $label", style = style.copy(color = color), modifier = Modifier.padding(4.dp, 2.dp))
    }
}

/** A line at the [snapped] time, if any, with a marker on each execution's sample there. */
@Composable
private fun XYGraphScope<Double, Double>.Crosshair(
    series: List<ChartSeries>,
    colors: List<Color>,
    snapped: State<Double?>,
) {
    val seconds = snapped.value ?: return
    val muted = JewelTheme.globalColors.text.info
    VerticalLineAnnotation(seconds, LineStyle(SolidColor(muted.copy(alpha = CROSSHAIR_ALPHA)), 1.dp))
    series.forEachIndexed { index, line ->
        val i = line.nearest(seconds) ?: return@forEachIndexed
        XYAnnotation(Point(line.secondsAt(i), line.values[i]), AnchorPoint.Center) {
            Box(
                Modifier
                    .size(MARKER.dp)
                    .background(colors[index], CircleShape)
                    .border(1.5.dp, JewelTheme.globalColors.panelBackground, CircleShape),
            )
        }
    }
}

/**
 * Places [content], for the [snapped] time, beside the pointer while it's
 * [hover]ing, flipping to its left near the right edge.
 */
@Composable
private fun HoverCard(
    hover: State<Hover?>,
    snapped: State<Double?>,
    content: @Composable (Double) -> Unit,
) {
    val position = hover.value?.position ?: return
    val seconds = snapped.value ?: return
    Layout(content = { content(seconds) }) { measurables, constraints ->
        val card = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gap = CARD_GAP.dp.roundToPx()
        val right = position.x.roundToInt() + gap
        val x = if (right + card.width <= constraints.maxWidth) right else position.x.roundToInt() - gap - card.width
        val y =
            (position.y.roundToInt() - card.height / 2).coerceIn(
                0,
                (constraints.maxHeight - card.height).coerceAtLeast(0),
            )
        layout(constraints.maxWidth, constraints.maxHeight) { card.place(x.coerceAtLeast(0), y) }
    }
}

@Composable
private fun HoverContent(
    series: List<ChartSeries>,
    colors: List<Color>,
    seconds: Double,
    ceiling: Double,
    format: (Double) -> String,
) {
    val shape = RoundedCornerShape(CORNER.dp)
    val background =
        retrieveColorOrUnspecified(
            "ToolTip.background",
        ).takeOrElse(JewelTheme.globalColors.panelBackground)
    val foreground = retrieveColorOrUnspecified("ToolTip.foreground").takeOrElse(JewelTheme.globalColors.text.normal)
    val muted = JewelTheme.globalColors.text.info
    val style = JewelTheme.typography.labelTextStyle.copy(color = foreground)
    // Highest first, so a busy chart's card leads with what stands out.
    val rows =
        series.indices
            .mapNotNull { index -> series[index].nearest(seconds)?.let { index to series[index].values[it] } }
            .sortedByDescending { it.second }
    Column(
        Modifier
            .shadow(CARD_ELEVATION.dp, shape)
            .background(background, shape)
            .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(formatSeconds(seconds), style = style.copy(color = muted))
        for ((index, value) in rows.take(MAX_CARD_ROWS)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(SWATCH.dp).background(colors[index], CircleShape))
                if (series.size > 1) Text(series[index].name, style = style)
                Text(format(value), style = style.copy(fontWeight = FontWeight.Bold))
                if (ceiling > 0) Text(percentOf(value, ceiling), style = style.copy(color = muted))
            }
        }
        if (rows.size > MAX_CARD_ROWS) Text("and ${rows.size - MAX_CARD_ROWS} more", style = style.copy(color = muted))
    }
}

@Composable
private fun Legend(
    series: List<ChartSeries>,
    colors: List<Color>,
) {
    val style = JewelTheme.typography.labelTextStyle.copy(color = JewelTheme.globalColors.text.info)
    FlowRow(
        Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        series.forEachIndexed { index, line ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(SWATCH.dp).background(colors[index], CircleShape))
                Text(line.name, style = style)
            }
        }
    }
}

/** A line's samples, at their time into the run. */
private fun points(line: ChartSeries): List<Point<Double, Double>> =
    line.values.mapIndexed { i, value -> Point(line.secondsAt(i), value) }

private fun Color.takeOrElse(fallback: Color): Color = if (this == Color.Unspecified) fallback else this

internal fun seriesColor(index: Int): Color = SERIES_COLORS[index % SERIES_COLORS.size].toComposeColor()

private val SERIES_COLORS =
    listOf(
        JBColor(0x3574F0, 0x548AF7),
        JBColor(0x5FB865, 0x57965C),
        JBColor(0xE08855, 0xC77D55),
        JBColor(0x955AE0, 0xA571E6),
        JBColor(0x3BA7B8, 0x2AACB8),
        JBColor(0xD4A72C, 0xD6AE58),
    )

private const val HEADROOM = 1.08
private const val CHART_HEIGHT = 200
private const val X_TICK_SPACING = 72
private const val Y_TICK_SPACING = 36
private const val GRID_ALPHA = 0.6f
private const val FILL_TOP = 0.3f
private const val CROSSHAIR_ALPHA = 0.7f
private const val DASH = 6f
private const val MARKER = 9
private const val SWATCH = 8
private const val CORNER = 6
private const val CARD_GAP = 14
private const val CARD_ELEVATION = 4
private const val MAX_CARD_ROWS = 8
