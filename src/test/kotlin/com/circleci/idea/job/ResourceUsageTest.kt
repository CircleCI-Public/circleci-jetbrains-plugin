package com.circleci.idea.job

import com.circleci.idea.api.models.ResourceUsageWire
import com.circleci.idea.api.models.V3Entity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

class ResourceUsageTest {
    @Test
    fun testMapsResponse() {
        // Trimmed from a real GET /api/v3/jobs/{id}/resource-usage response; memory has one sample fewer.
        val json =
            """
            {"data": {"attributes": {
              "resource_class": {"name": "large-gen3", "cpu_count": 4, "memory_limit_bytes": 17179869184},
              "parallel_executions": [{"execution": 0, "interval_ms": 15000,
                "cpu_cores": [0.05, 1.75, 3.53], "memory_bytes": [774324224, 1606578176],
                "network_rx_bytes": 10, "network_tx_bytes": 20}]}}}
            """.trimIndent()
        val wire: V3Entity<ResourceUsageWire> =
            Gson().fromJson(
                json,
                object : TypeToken<V3Entity<ResourceUsageWire>>() {}.type,
            )
        val usage = ResourceUsage.of(wire.data!!)

        assertEquals("resource class", "large-gen3", usage.resourceClass)
        assertEquals("cpu limit", 4.0, usage.cpuLimit, 0.0)
        val execution = usage.executions.single()
        assertEquals("samples both series have", 2, execution.cpuCores.size)
        assertEquals("interval", Duration.ofSeconds(15), execution.interval)
        assertEquals("duration", Duration.ofSeconds(30), execution.duration)
        assertEquals("network in", 10L, execution.networkRxBytes)
    }

    @Test
    fun testStats() {
        val stats = UsageStats.of(listOf(1.0, 2.0, 3.0), limit = 4.0)!!
        assertEquals("min", 1.0, stats.min, 0.0)
        assertEquals("mean", 2.0, stats.mean, 0.0)
        assertEquals("max", 3.0, stats.max, 0.0)
        assertEquals("peak of limit", 75.0, stats.peakPercentOfLimit!!, 0.0)

        assertNull("no limit, no percentage", UsageStats.of(listOf(1.0), limit = 0.0)!!.peakPercentOfLimit)
        assertNull("no samples", UsageStats.of(emptyList(), limit = 4.0))
    }

    @Test
    fun testChartSamples() {
        val line = ChartSeries("Execution 0", listOf(1.0, 2.0, 3.0), Duration.ofSeconds(15))
        assertEquals("last sample's time", 30.0, line.lastSeconds, 0.0)
        assertEquals("nearest rounds", 1, line.nearest(20.0))
        assertEquals("before the run", 0, line.nearest(-5.0))
        assertEquals("past the end", 2, line.nearest(90.0))
        assertNull("no samples", ChartSeries("Execution 1", emptyList(), Duration.ofSeconds(15)).nearest(0.0))

        val shorter = ChartSeries("Execution 1", listOf(1.0), Duration.ofSeconds(15))
        assertEquals("snaps to the longest series' samples", 15.0, snap(listOf(shorter, line), 10.0)!!, 0.0)
        assertNull("nothing to snap to", snap(emptyList(), 10.0))
    }

    @Test
    fun testChartExtents() {
        val line = ChartSeries("Execution 0", listOf(1.0, 6.0), Duration.ofSeconds(15))
        assertEquals("time reaches the last sample", 15.0, timeExtent(listOf(line)), 0.0)
        assertEquals("no samples still has a range", 1.0, timeExtent(emptyList()), 0.0)
        assertEquals("values above the limit", 6.0, valueExtent(listOf(line), ceiling = 4.0), 0.0)
        assertEquals("limit above the values", 8.0, valueExtent(listOf(line), ceiling = 8.0), 0.0)
        assertEquals("nothing still has a range", 1.0, valueExtent(emptyList(), ceiling = 0.0), 0.0)
    }

    @Test
    fun testFormatting() {
        assertEquals("percent of limit", "63%", percentOf(2.5, 4.0))
        assertEquals("seconds into the run", "1m 30s", formatSeconds(90.0))
        assertEquals("bytes", "512 B", formatBytes(512.0))
        assertEquals("mebibytes", "1.5 MiB", formatBytes(1.5 * 1024 * 1024))
        assertEquals("gibibytes", "16.0 GiB", formatBytes(17179869184.0))
        assertEquals("cores", "3.53", formatCores(3.5331))
    }
}
