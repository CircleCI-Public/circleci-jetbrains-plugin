package com.circleci.idea.job

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.circleci.idea.run.formatElapsed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.typography

/**
 * A job's CPU and memory use as charts against its resource class's limits,
 * with each execution's min, mean, max and peak share of the limit, as
 * `circleci job resource-usage get` reports them.
 */
class ResourceUsageTab(
    private val ref: JobRef,
    private val scope: CoroutineScope,
    private val service: JobDetailsService,
) {
    private val _state = MutableStateFlow<UsageState>(UsageState.Message("Resource usage loads once the job ends"))
    val state: StateFlow<UsageState> = _state.asStateFlow()

    @Composable
    fun View() {
        val current by state.collectAsState()
        ResourceUsageView(current)
    }

    /** Fetch and show the job's usage. Call on the EDT. */
    fun load() {
        _state.value = UsageState.Message("Loading resource usage...")
        scope.launch {
            _state.value =
                service.fetchResourceUsage(ref.jobId).fold(
                    onSuccess = { usage ->
                        when {
                            usage == null -> UsageState.Message(NO_USAGE)
                            usage.executions.all { it.cpuCores.isEmpty() } ->
                                UsageState.Message("No executions recorded any usage")
                            else -> UsageState.Loaded(usage)
                        }
                    },
                    onFailure = { UsageState.Message("Failed to load resource usage: ${it.message}") },
                )
        }
    }

    private companion object {
        const val NO_USAGE = "No resource usage recorded. Approval jobs, and jobs canceled before they ran, have none."
    }
}

/** What the resource usage tab shows. */
sealed interface UsageState {
    data class Message(val text: String) : UsageState

    data class Loaded(val usage: ResourceUsage) : UsageState
}

@Composable
private fun ResourceUsageView(state: UsageState) {
    VerticallyScrollableContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(PADDING.dp),
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP.dp),
        ) {
            when (state) {
                is UsageState.Message -> Text(state.text, color = JewelTheme.globalColors.text.info)
                is UsageState.Loaded -> UsageView(state.usage)
            }
        }
    }
}

@Composable
private fun UsageView(usage: ResourceUsage) {
    Text(summary(usage), color = JewelTheme.globalColors.text.info)
    for (metric in UsageMetric.entries) {
        val limit = metric.limit(usage)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(metric.title, style = JewelTheme.typography.h4TextStyle)
                peak(usage, metric)?.let { Text(it, color = JewelTheme.globalColors.text.info) }
            }
            UsageChart(
                series = usage.executions.map { ChartSeries("Execution ${it.index}", metric.values(it), it.interval) },
                ceiling = limit,
                format = metric::format,
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Summary", style = JewelTheme.typography.h4TextStyle)
        StatsTable(usage)
    }
}

@Composable
private fun StatsTable(usage: ResourceUsage) {
    val muted = JewelTheme.globalColors.text.info
    Column {
        TableRow {
            for (heading in listOf("Execution", "Metric", "Min", "Mean", "Max", "Peak of limit")) {
                Text(heading, color = muted, modifier = Modifier.weight(1f))
            }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        for (execution in usage.executions) {
            for (metric in UsageMetric.entries) {
                val stats = UsageStats.of(metric.values(execution), metric.limit(usage)) ?: continue
                TableRow {
                    Text("${execution.index}", modifier = Modifier.weight(1f))
                    Text(metric.title, modifier = Modifier.weight(1f))
                    Text(metric.format(stats.min), modifier = Modifier.weight(1f))
                    Text(metric.format(stats.mean), modifier = Modifier.weight(1f))
                    Text(metric.format(stats.max), modifier = Modifier.weight(1f))
                    PeakCell(stats.peakPercentOfLimit, Modifier.weight(1f))
                }
            }
            TableRow {
                Text("${execution.index}", modifier = Modifier.weight(1f))
                Text("Network", modifier = Modifier.weight(1f))
                Text(
                    "in ${formatBytes(execution.networkRxBytes.toDouble())} · " +
                        "out ${formatBytes(execution.networkTxBytes.toDouble())}",
                    color = muted,
                    modifier = Modifier.weight(4f),
                )
            }
        }
    }
}

@Composable
private fun TableRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A peak's share of the limit as a small meter: under half suggests a smaller resource class. */
@Composable
private fun PeakCell(
    percent: Double?,
    modifier: Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (percent == null) {
            Text("-")
            return@Row
        }
        val colors = JewelTheme.globalColors.text
        val fill =
            when {
                percent >= HIGH_PERCENT -> colors.error
                percent >= LOW_PERCENT -> seriesColor(0)
                else -> colors.warning
            }
        val shape = RoundedCornerShape(2.dp)
        Box(
            Modifier
                .width(METER_WIDTH.dp)
                .height(METER_HEIGHT.dp)
                .background(JewelTheme.globalColors.borders.normal.copy(alpha = TRACK_ALPHA), shape),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((percent / FULL_PERCENT).coerceIn(0.0, 1.0).toFloat())
                    .background(fill.takeIf { it != Color.Unspecified } ?: seriesColor(0), shape),
            )
        }
        Text(percentOf(percent, FULL_PERCENT), fontWeight = FontWeight.Medium)
    }
}

/** A metric's highest sample across executions, and its share of the limit. */
private fun peak(
    usage: ResourceUsage,
    metric: UsageMetric,
): String? {
    val max = usage.executions.mapNotNull { metric.values(it).maxOrNull() }.maxOrNull() ?: return null
    val limit = metric.limit(usage)
    return if (limit > 0) {
        "peak ${metric.format(
            max,
        )} · ${percentOf(max, limit)} of limit"
    } else {
        "peak ${metric.format(max)}"
    }
}

private fun summary(usage: ResourceUsage): String {
    val parts = mutableListOf(usage.resourceClass ?: "Unknown resource class")
    if (usage.cpuLimit > 0) parts.add("${formatCores(usage.cpuLimit).removeSuffix(".00")} CPUs")
    if (usage.memoryLimitBytes > 0) parts.add("${formatBytes(usage.memoryLimitBytes.toDouble())} memory")
    val intervals = usage.executions.map { it.interval }.distinct()
    intervals.singleOrNull()?.let { parts.add("sampled every ${formatElapsed(it)}") }
    if (usage.executions.size > 1) parts.add("${usage.executions.size} parallel executions")
    return parts.joinToString(" · ")
}

private const val PADDING = 12
private const val SECTION_GAP = 20
private const val METER_WIDTH = 48
private const val METER_HEIGHT = 6
private const val TRACK_ALPHA = 0.5f
private const val FULL_PERCENT = 100.0
private const val HIGH_PERCENT = 90.0
private const val LOW_PERCENT = 50.0
