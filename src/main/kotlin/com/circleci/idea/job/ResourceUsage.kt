package com.circleci.idea.job

import com.circleci.idea.api.models.ExecutionUsageWire
import com.circleci.idea.api.models.ResourceUsageWire
import com.circleci.idea.run.formatElapsed
import java.time.Duration
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A job's CPU and memory use, sampled at an interval through each parallel
 * execution, against its resource class's limits.
 */
data class ResourceUsage(
    val resourceClass: String?,
    // Zero when the API reported no limit; a chart then scales to the data.
    val cpuLimit: Double,
    val memoryLimitBytes: Long,
    val executions: List<ExecutionUsage>,
) {
    companion object {
        fun of(wire: ResourceUsageWire): ResourceUsage {
            val attributes = wire.attributes
            val resourceClass = attributes?.resourceClass
            return ResourceUsage(
                resourceClass = resourceClass?.name,
                cpuLimit = resourceClass?.cpuCount ?: 0.0,
                memoryLimitBytes = resourceClass?.memoryLimitBytes ?: 0L,
                executions = attributes?.parallelExecutions.orEmpty().mapIndexed(::executionUsage),
            )
        }

        private fun executionUsage(
            position: Int,
            execution: ExecutionUsageWire,
        ): ExecutionUsage {
            // Only as many samples as both series have, as the CLI reads them.
            val cpu = execution.cpuCores.orEmpty()
            val memory = execution.memoryBytes.orEmpty()
            val samples = minOf(cpu.size, memory.size)
            return ExecutionUsage(
                index = execution.execution ?: position,
                interval = Duration.ofMillis(execution.intervalMs ?: 0),
                cpuCores = cpu.take(samples),
                memoryBytes = memory.take(samples).map { it.toDouble() },
                networkRxBytes = execution.networkRxBytes ?: 0,
                networkTxBytes = execution.networkTxBytes ?: 0,
            )
        }
    }
}

data class ExecutionUsage(
    val index: Int,
    val interval: Duration,
    val cpuCores: List<Double>,
    val memoryBytes: List<Double>,
    val networkRxBytes: Long,
    val networkTxBytes: Long,
) {
    val duration: Duration
        get() = interval.multipliedBy(cpuCores.size.toLong())
}

/** One line on a [UsageChart]: samples taken every [interval]. */
data class ChartSeries(
    val name: String,
    val values: List<Double>,
    val interval: Duration,
) {
    private val step: Double get() = interval.toMillis() / 1000.0

    /** Seconds into the run of each sample. */
    fun secondsAt(index: Int): Double = step * index

    /** The sample nearest [seconds] into the run, or null with no samples. */
    fun nearest(seconds: Double): Int? {
        if (values.isEmpty()) return null
        if (step <= 0) return 0
        return (seconds / step).roundToInt().coerceIn(0, values.lastIndex)
    }

    val lastSeconds: Double get() = secondsAt((values.size - 1).coerceAtLeast(0))
}

/** The sample time nearest [seconds]. The longest series sets the snap points. */
internal fun snap(
    series: List<ChartSeries>,
    seconds: Double,
): Double? {
    val longest = series.maxByOrNull { it.lastSeconds } ?: return null
    return longest.nearest(seconds)?.let(longest::secondsAt)
}

/** How far into the run the chart reaches; one second with no samples, so it still has a range. */
internal fun timeExtent(series: List<ChartSeries>): Double =
    (series.maxOfOrNull { it.lastSeconds } ?: 0.0).takeIf { it > 0 } ?: 1.0

/** The highest of the samples and [ceiling]; one with neither, so the chart still has a range. */
internal fun valueExtent(
    series: List<ChartSeries>,
    ceiling: Double,
): Double = maxOf(ceiling, series.maxOfOrNull { it.values.maxOrNull() ?: 0.0 } ?: 0.0).takeIf { it > 0 } ?: 1.0

/**
 * A series' summary. [peakPercentOfLimit] is the headline: under 50% on both
 * CPU and memory means a smaller resource class would do.
 */
data class UsageStats(
    val min: Double,
    val mean: Double,
    val max: Double,
    val peakPercentOfLimit: Double?,
) {
    companion object {
        fun of(
            values: List<Double>,
            limit: Double,
        ): UsageStats? {
            if (values.isEmpty()) return null
            val max = values.max()
            return UsageStats(
                min = values.min(),
                mean = values.average(),
                max = max,
                peakPercentOfLimit = if (limit > 0) max / limit * PERCENT else null,
            )
        }

        private const val PERCENT = 100
    }
}

/** How a metric's values read, in charts and tables. */
enum class UsageMetric(val title: String) {
    CPU("CPU (cores)"),
    MEMORY("Memory"),
    ;

    fun values(execution: ExecutionUsage): List<Double> = if (this == CPU) execution.cpuCores else execution.memoryBytes

    fun limit(usage: ResourceUsage): Double = if (this == CPU) usage.cpuLimit else usage.memoryLimitBytes.toDouble()

    fun format(value: Double): String = if (this == CPU) formatCores(value) else formatBytes(value)
}

internal fun formatSeconds(seconds: Double): String =
    formatElapsed(
        Duration.ofMillis((seconds * 1000.0).toLong()),
    )

internal fun percentOf(
    value: Double,
    limit: Double,
): String = String.format(Locale.ROOT, "%.0f%%", value / limit * 100)

internal fun formatCores(cores: Double): String = String.format(Locale.ROOT, "%.2f", cores)

/** Bytes in binary units, e.g. "1.5 GiB". */
internal fun formatBytes(bytes: Double): String {
    var value = bytes
    var unit = 0
    while (value >= BYTES_PER_KIB && unit < BYTE_UNITS.lastIndex) {
        value /= BYTES_PER_KIB
        unit++
    }
    return if (unit == 0) "${value.toLong()} B" else String.format(Locale.ROOT, "%.1f %s", value, BYTE_UNITS[unit])
}

private const val BYTES_PER_KIB = 1024.0
private val BYTE_UNITS = listOf("B", "KiB", "MiB", "GiB", "TiB")
