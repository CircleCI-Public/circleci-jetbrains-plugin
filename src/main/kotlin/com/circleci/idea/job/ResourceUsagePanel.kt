package com.circleci.idea.job

import com.circleci.idea.run.formatElapsed
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Component
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableModel

/**
 * A job's CPU and memory use as charts against its resource class's limits,
 * with each execution's min, mean, max and peak share of the limit, as
 * `circleci job resource-usage get` reports them.
 */
class ResourceUsagePanel(
    private val ref: JobRef,
    private val scope: CoroutineScope,
    private val service: JobDetailsService,
) : JBPanel<ResourceUsagePanel>(BorderLayout()) {
    private val content =
        JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }

    init {
        add(JBScrollPane(content), BorderLayout.CENTER)
        showMessage("Resource usage loads once the job ends")
    }

    /** Fetch and show the job's usage. Call on the EDT. */
    fun load() {
        showMessage("Loading resource usage...")
        scope.launch {
            service.fetchResourceUsage(ref.jobId).fold(
                onSuccess = { usage ->
                    when {
                        usage == null ->
                            showMessage(NO_USAGE)
                        usage.executions.all { it.cpuCores.isEmpty() } ->
                            showMessage(
                                "No executions recorded any usage",
                            )
                        else -> show(usage)
                    }
                },
                onFailure = { showMessage("Failed to load resource usage: ${it.message}") },
            )
        }
    }

    private fun show(usage: ResourceUsage) {
        content.removeAll()
        content.add(left(JBLabel(summary(usage))))
        for (metric in UsageMetric.entries) {
            content.add(left(JBLabel(metric.title).apply { font = JBUI.Fonts.label().asBold() }))
            val series =
                usage.executions.map { ChartSeries("Execution ${it.index}", metric.values(it), it.interval) }
            content.add(left(UsageChart(series, metric.limit(usage), metric::format)))
        }
        content.add(left(JBLabel("Summary").apply { font = JBUI.Fonts.label().asBold() }))
        content.add(left(statsTable(usage)))
        content.revalidate()
        content.repaint()
    }

    private fun statsTable(usage: ResourceUsage): JComponent {
        val model =
            object : DefaultTableModel(arrayOf("Execution", "Metric", "Min", "Mean", "Max", "Peak of limit"), 0) {
                override fun isCellEditable(
                    row: Int,
                    column: Int,
                ): Boolean = false
            }
        for (execution in usage.executions) {
            for (metric in UsageMetric.entries) {
                val stats = UsageStats.of(metric.values(execution), metric.limit(usage)) ?: continue
                model.addRow(
                    arrayOf<Any>(
                        execution.index,
                        metric.title,
                        metric.format(stats.min),
                        metric.format(stats.mean),
                        metric.format(stats.max),
                        stats.peakPercentOfLimit?.let { String.format(Locale.ROOT, "%.0f%%", it) } ?: "-",
                    ),
                )
            }
            model.addRow(
                arrayOf<Any>(
                    execution.index,
                    "Network",
                    "",
                    "",
                    "",
                    "in ${formatBytes(
                        execution.networkRxBytes.toDouble(),
                    )} · out ${formatBytes(execution.networkTxBytes.toDouble())}",
                ),
            )
        }
        val table = JBTable(model).apply { setShowGrid(false) }
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(table.tableHeader, BorderLayout.NORTH)
            add(table, BorderLayout.CENTER)
        }
    }

    private fun showMessage(text: String) {
        content.removeAll()
        content.add(left(JBLabel(text, SwingConstants.LEFT).apply { foreground = UIUtil.getContextHelpForeground() }))
        content.revalidate()
        content.repaint()
    }

    private fun left(component: JComponent): Component {
        component.alignmentX = Component.LEFT_ALIGNMENT
        component.border = JBUI.Borders.emptyBottom(GAP)
        return component
    }

    private companion object {
        const val GAP = 8
        const val NO_USAGE = "No resource usage recorded. Approval jobs, and jobs canceled before they ran, have none."

        fun summary(usage: ResourceUsage): String {
            val parts = mutableListOf(usage.resourceClass ?: "Unknown resource class")
            if (usage.cpuLimit > 0) parts.add("${formatCores(usage.cpuLimit).removeSuffix(".00")} CPUs")
            if (usage.memoryLimitBytes > 0) parts.add("${formatBytes(usage.memoryLimitBytes.toDouble())} memory")
            val intervals = usage.executions.map { it.interval }.distinct()
            intervals.singleOrNull()?.let { parts.add("sampled every ${formatElapsed(it)}") }
            if (usage.executions.size > 1) parts.add("${usage.executions.size} parallel executions")
            return parts.joinToString(" · ")
        }
    }
}
