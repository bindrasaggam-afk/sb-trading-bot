package com.sb.researchanalytics.dashboard

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.sb.researchanalytics.data.ResearchAnalyticsRepository
import com.sb.researchanalytics.domain.AnalyticsFilter
import com.sb.researchanalytics.domain.AnalyticsTime
import com.sb.researchanalytics.domain.ExportHistoryEntry
import com.sb.researchanalytics.domain.GroupPerformance
import com.sb.researchanalytics.domain.ResearchAnalyticsEngine
import com.sb.researchanalytics.domain.ResearchAnalyticsReport
import com.sb.researchanalytics.export.ExportFormat
import com.sb.researchanalytics.export.ResearchExportManager
import com.sb.signals.domain.SignalCategory
import com.sb.signals.domain.SignalDirection
import com.sb.ui.theme.AppDesign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class ResearchAnalyticsScreen private constructor(
    private val context: Context,
    private val container: LinearLayout,
    private val onOpenChart: (String, String) -> Unit
) {

    private val scope: CoroutineScope =
        (context as? LifecycleOwner)?.lifecycleScope
            ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repository = ResearchAnalyticsRepository(context)
    private val exportManager = ResearchExportManager(context)
    private val content = LinearLayout(context)
    private var filter = AnalyticsFilter()
    private var report: ResearchAnalyticsReport? = null
    private var page = 0
    private var loading = false
    private var lastExportMessage: String = ""

    private lateinit var statusText: TextView
    private lateinit var filterText: TextView
    private lateinit var summaryContainer: LinearLayout
    private lateinit var chartContainer: LinearLayout
    private lateinit var qualityContainer: LinearLayout
    private lateinit var historyContainer: LinearLayout
    private lateinit var tableContainer: LinearLayout
    private lateinit var paginationContainer: LinearLayout

    fun create() {
        container.removeAllViews()
        container.addView(content)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(
            dp(14), dp(14), dp(14), dp(24)
        )
        buildHeader()
        buildFilters()
        buildSummary()
        buildCharts()
        buildActions()
        buildQuality()
        buildTable()
        buildHistory()
        load()
    }

    private fun buildHeader() {
        val title = AppDesign.text(
            context,
            "Research & Analytics",
            23f,
            AppDesign.textPrimary,
            bold = true
        )
        content.addView(title)
        content.addView(
            AppDesign.text(
                context,
                "Read-only analytics center • No broker order • " +
                    "No strategy changes",
                12f,
                AppDesign.accent
            )
        )
        statusText = AppDesign.text(
            context,
            "Loading research data...",
            12f,
            AppDesign.textMuted
        ).apply {
            setPadding(0, dp(8), 0, dp(4))
        }
        content.addView(statusText)
    }

    private fun buildFilters() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(chip("All") {
            filter = AnalyticsFilter()
            page = 0
            load()
        })
        row.addView(chip("30D") {
            val now = System.currentTimeMillis()
            filter = filter.copy(
                fromMillis = now - 30L * 24 * 3_600_000,
                toMillis = now
            )
            page = 0
            load()
        })
        row.addView(chip("90D") {
            val now = System.currentTimeMillis()
            filter = filter.copy(
                fromMillis = now - 90L * 24 * 3_600_000,
                toMillis = now
            )
            page = 0
            load()
        })
        row.addView(chip("1Y") {
            val now = System.currentTimeMillis()
            filter = filter.copy(
                fromMillis = now - 365L * 24 * 3_600_000,
                toMillis = now
            )
            page = 0
            load()
        })
        row.addView(chip("Filters") { showFilterMenu() })
        content.addView(row)
        filterText = AppDesign.text(
            context,
            filter.rangeLabel(),
            11f,
            AppDesign.textMuted
        ).apply {
            setPadding(0, dp(6), 0, dp(4))
        }
        content.addView(filterText)
    }

    private fun buildSummary() {
        summaryContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(sectionLabel("Overview"))
        content.addView(summaryContainer)
    }

    private fun buildCharts() {
        content.addView(sectionLabel("Indicator edge"))
        chartContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(chartContainer)
    }

    private fun buildActions() {
        content.addView(sectionLabel("Export center"))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        row.addView(button("Export XLSX") {
            export(ExportFormat.XLSX)
        })
        row.addView(button("Export CSV") {
            export(ExportFormat.CSV)
        })
        row.addView(button("Refresh") {
            load()
        })
        content.addView(row)
        content.addView(
            AppDesign.text(
                context,
                "Exports use the current filter and the data-through " +
                    "timestamp. Pending paper trades stay pending.",
                11f,
                AppDesign.textMuted
            )
        )
    }

    private fun buildQuality() {
        content.addView(sectionLabel("Data quality & reconciliation"))
        qualityContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(qualityContainer)
    }

    private fun buildTable() {
        content.addView(sectionLabel("Signal outcomes"))
        tableContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(tableContainer)
        paginationContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        content.addView(paginationContainer)
    }

    private fun buildHistory() {
        content.addView(sectionLabel("Recent exports"))
        historyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(historyContainer)
    }

    private fun load() {
        if (loading) return
        loading = true
        statusText.text = "Loading analytics..."
        statusText.setTextColor(AppDesign.textMuted)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dataset = repository.load(filter)
                    val report = ResearchAnalyticsEngine.analyze(dataset)
                    val history = exportManager.recentExports(5)
                    report to history
                }
            }
            loading = false
            result.onSuccess { (loaded, history) ->
                report = loaded
                render(loaded, history)
            }.onFailure { error ->
                statusText.text = "Analytics failed: " +
                    (error.message ?: "unknown error")
                statusText.setTextColor(AppDesign.negative)
            }
        }
    }

    private fun render(
        report: ResearchAnalyticsReport,
        history: List<ExportHistoryEntry>
    ) {
        filterText.text = filter.rangeLabel() +
            " • Data through " +
            AnalyticsTime.formatDateTime(report.dataset.dataThrough)
        statusText.text = "${report.research.total} selected signals • " +
            "${report.research.completed} research closed • " +
            "${report.paper.pending} paper pending"
        statusText.setTextColor(AppDesign.textMuted)
        renderSummary(report)
        renderCharts(report)
        renderQuality(report)
        renderTable(report)
        renderHistory(history)
    }

    private fun renderSummary(report: ResearchAnalyticsReport) {
        summaryContainer.removeAllViews()
        addMetricRow(
            summaryContainer,
            "Signals" to report.research.total.toString(),
            "Research win rate" to percent(report.research.winRate),
            "Paper win rate" to percent(report.paper.winRate)
        )
        addMetricRow(
            summaryContainer,
            "Research avg return" to percent(
                report.research.averageReturnPct
            ),
            "Research avg R" to number(report.research.averageR),
            "Paper avg return" to percent(
                report.paper.averageReturnPct
            )
        )
        addMetricRow(
            summaryContainer,
            "Pending research" to report.research.pending.toString(),
            "Pending paper" to report.paper.pending.toString(),
            "Mismatches" to report.reconciliation
                .outcomeMismatches.toString()
        )
        addMetricRow(
            summaryContainer,
            "Captured snapshots" to report.dataQuality
                .capturedSnapshots.toString(),
            "Reconstructed" to report.dataQuality
                .reconstructedSnapshots.toString(),
            "Not available" to report.dataQuality
                .unavailableSnapshots.toString()
        )
    }

    private fun renderCharts(report: ResearchAnalyticsReport) {
        chartContainer.removeAllViews()
        val indicatorRows = report.indicators
            .filter { it.confirmed && it.summary.completed > 0 }
            .sortedByDescending { it.summary.winRate ?: -1.0 }
            .take(8)
        if (indicatorRows.isEmpty()) {
            chartContainer.addView(
                note("No completed indicator samples yet.")
            )
            return
        }
        indicatorRows.forEach { stat ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(6))
            }
            val label = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            label.addView(
                AppDesign.text(
                    context,
                    stat.indicator,
                    12f,
                    AppDesign.textPrimary,
                    bold = true
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
            )
            label.addView(
                AppDesign.text(
                    context,
                    "${percent(stat.summary.winRate)} " +
                        "(${stat.summary.completed})",
                    12f,
                    AppDesign.textMuted
                )
            )
            row.addView(label)
            val bar = ProgressBar(
                context,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = 100
                progress = (stat.summary.winRate ?: 0.0).toInt()
                progressTintList = android.content.res.ColorStateList.valueOf(
                    AppDesign.accent
                )
            }
            row.addView(bar)
            chartContainer.addView(row)
        }
        chartContainer.addView(
            note(
                "True-confirmation win rate. Sample counts are shown " +
                    "beside each bar."
            )
        )
    }

    private fun renderQuality(report: ResearchAnalyticsReport) {
        qualityContainer.removeAllViews()
        qualityContainer.addView(
            note(
                "Research ${report.reconciliation.researchSignals} • " +
                    "Paper orders ${report.reconciliation.paperOrders} • " +
                    "Linked ${report.reconciliation.linkedSignals} • " +
                    "Research-only ${report.reconciliation
                        .researchWithoutPaper} • " +
                    "Orphan paper ${report.reconciliation
                        .orphanPaperOrders}"
            )
        )
        if (report.dataQuality.issues.isEmpty()) {
            qualityContainer.addView(note("No data-quality issues found."))
        } else {
            report.dataQuality.issues.take(8).forEach { issue ->
                val color = if (issue.severity == "ERROR") {
                    AppDesign.negative
                } else {
                    AppDesign.accent
                }
                qualityContainer.addView(
                    AppDesign.text(
                        context,
                        "${issue.severity}: ${issue.category} — " +
                            issue.message,
                        11f,
                        color
                    ).apply {
                        setPadding(0, dp(2), 0, dp(2))
                    }
                )
            }
        }
    }

    private fun renderTable(report: ResearchAnalyticsReport) {
        tableContainer.removeAllViews()
        val rows = report.dataset.rows
        val pageSize = 15
        val pageCount = maxOf(1, (rows.size + pageSize - 1) / pageSize)
        if (page >= pageCount) page = pageCount - 1
        if (rows.isEmpty()) {
            tableContainer.addView(note("No signals match this filter."))
            renderPagination(0, 0, 0)
            return
        }
        val start = page * pageSize
        val pageRows = rows.drop(start).take(pageSize)
        val horizontal = HorizontalScrollView(context)
        val table = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val widths = intArrayOf(74, 90, 42, 92, 58, 78, 92)
        table.addView(
            tableRow(
                listOf(
                    "Generated",
                    "Symbol",
                    "Side",
                    "Status",
                    "Net %",
                    "Paper",
                    "Snapshot"
                ),
                widths,
                header = true
            )
        )
        pageRows.forEach { row ->
            val signal = row.signal
            val tr = tableRow(
                listOf(
                    AnalyticsTime.formatDate(signal.generatedAt),
                    signal.symbol,
                    signal.direction.label,
                    row.researchState,
                    number(row.researchReturnPct),
                    row.paperState,
                    row.snapshotSource
                ),
                widths,
                header = false
            )
            tr.setOnClickListener {
                onOpenChart(signal.symbol, signal.exchange)
            }
            table.addView(tr)
        }
        horizontal.addView(table)
        tableContainer.addView(horizontal)
        renderPagination(page, pageCount, rows.size)
    }

    private fun renderPagination(
        page: Int,
        pageCount: Int,
        total: Int
    ) {
        paginationContainer.removeAllViews()
        val previous = button("Previous") {
            if (this.page > 0) {
                this.page--
                report?.let(::renderTable)
            }
        }
        previous.isEnabled = page > 0
        val next = button("Next") {
            if (this.page < pageCount - 1) {
                this.page++
                report?.let(::renderTable)
            }
        }
        next.isEnabled = page < pageCount - 1
        paginationContainer.addView(previous)
        paginationContainer.addView(
            AppDesign.text(
                context,
                "Page ${page + 1} / $pageCount • $total signals",
                11f,
                AppDesign.textMuted
            ).apply {
                setPadding(dp(8), 0, dp(8), 0)
            }
        )
        paginationContainer.addView(next)
    }

    private fun renderHistory(history: List<ExportHistoryEntry>) {
        historyContainer.removeAllViews()
        if (history.isEmpty()) {
            historyContainer.addView(note("No exports saved yet."))
            return
        }
        history.forEach { entry ->
            historyContainer.addView(
                AppDesign.text(
                    context,
                    "${AnalyticsTime.formatDateTime(entry.createdAt)} • " +
                        "${entry.format} • ${entry.rowCount} rows • " +
                        entry.fileName,
                    11f,
                    AppDesign.textMuted
                ).apply {
                    setPadding(0, dp(2), 0, dp(2))
                }
            )
        }
        if (lastExportMessage.isNotBlank()) {
            historyContainer.addView(
                AppDesign.text(
                    context,
                    lastExportMessage,
                    11f,
                    AppDesign.positive
                )
            )
        }
    }

    private fun export(format: ExportFormat) {
        val current = report ?: return
        statusText.text = "Building ${format.name} export..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    exportManager.export(current, format)
                }
            }
            result.onSuccess { exported ->
                lastExportMessage = "${exported.fileName} • " +
                    "${exported.rowCount} rows • ${exported.message}"
                statusText.text = lastExportMessage
                statusText.setTextColor(AppDesign.positive)
                val history = withContext(Dispatchers.IO) {
                    runCatching {
                        exportManager.recentExports(5)
                    }.getOrDefault(emptyList())
                }
                renderHistory(history)
                showExportDialog(exported.fileName, exported.uri, format)
            }.onFailure { error ->
                statusText.text = "Export failed: " +
                    (error.message ?: "unknown error")
                statusText.setTextColor(AppDesign.negative)
            }
        }
    }

    private fun showExportDialog(
        fileName: String,
        uri: android.net.Uri,
        format: ExportFormat
    ) {
        val dialog = AlertDialog.Builder(context)
            .setTitle("Export ready")
            .setMessage("$fileName\n\nShare this file?")
            .setPositiveButton("Share") { _, _ ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = format.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(intent, "Share analytics export")
                )
            }
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnShowListener { AppDesign.styleDialog(dialog) }
        dialog.show()
    }

    private fun showFilterMenu() {
        val actions = arrayOf(
            "Category",
            "Direction",
            "Timeframe",
            "Paper state",
            "Symbol",
            "Strategy",
            "Minimum confidence",
            "Custom date range",
            "Clear filters"
        )
        AlertDialog.Builder(context)
            .setTitle("Research filters")
            .setItems(actions) { _, index ->
                when (index) {
                    0 -> multiChoice(
                        "Category",
                        SignalCategory.values().toList(),
                        filter.categories
                    ) { selected ->
                        filter = filter.copy(categories = selected)
                        reloadFilter()
                    }
                    1 -> multiChoice(
                        "Direction",
                        SignalDirection.values().toList(),
                        filter.directions
                    ) { selected ->
                        filter = filter.copy(directions = selected)
                        reloadFilter()
                    }
                    2 -> multiChoiceText(
                        "Timeframe",
                        listOf(
                            "1m", "3m", "5m", "10m", "15m", "30m",
                            "1h", "4h", "1D", "1W", "1M"
                        ),
                        filter.timeframes
                    ) { selected ->
                        filter = filter.copy(timeframes = selected)
                        reloadFilter()
                    }
                    3 -> paperFilterDialog()
                    4 -> textFilter(
                        "Symbol",
                        filter.symbols.joinToString(",")
                    ) { value ->
                        filter = filter.copy(
                            symbols = splitValues(value)
                        )
                        reloadFilter()
                    }
                    5 -> textFilter(
                        "Strategy",
                        filter.strategies.joinToString(",")
                    ) { value ->
                        filter = filter.copy(
                            strategies = splitValues(value)
                        )
                        reloadFilter()
                    }
                    6 -> textFilter(
                        "Minimum signal confidence",
                        filter.minConfidence?.toString().orEmpty()
                    ) { value ->
                        filter = filter.copy(
                            minConfidence = value.toDoubleOrNull()
                        )
                        reloadFilter()
                    }
                    7 -> dateRangeDialog()
                    8 -> {
                        filter = AnalyticsFilter()
                        page = 0
                        load()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun paperFilterDialog() {
        val options = arrayOf(
            "All signals",
            "Linked to paper",
            "Paper pending",
            "Paper closed",
            "Research only"
        )
        val values = arrayOf(
            AnalyticsFilter.ALL,
            AnalyticsFilter.PAPER_LINKED,
            AnalyticsFilter.PAPER_PENDING,
            AnalyticsFilter.PAPER_CLOSED,
            AnalyticsFilter.RESEARCH_ONLY
        )
        val selected = values.indexOf(filter.paperFilter)
            .coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle("Paper state")
            .setSingleChoiceItems(options, selected) { dialog, which ->
                filter = filter.copy(paperFilter = values[which])
                dialog.dismiss()
                reloadFilter()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dateRangeDialog() {
        val from = Calendar.getInstance()
        filter.fromMillis?.let { from.timeInMillis = it }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                from.set(year, month, day, 0, 0, 0)
                from.set(Calendar.MILLISECOND, 0)
                val to = Calendar.getInstance()
                filter.toMillis?.let { to.timeInMillis = it }
                DatePickerDialog(
                    context,
                    { _, toYear, toMonth, toDay ->
                        to.set(toYear, toMonth, toDay, 23, 59, 59)
                        to.set(Calendar.MILLISECOND, 999)
                        filter = filter.copy(
                            fromMillis = from.timeInMillis,
                            toMillis = to.timeInMillis
                        )
                        reloadFilter()
                    },
                    to.get(Calendar.YEAR),
                    to.get(Calendar.MONTH),
                    to.get(Calendar.DAY_OF_MONTH)
                ).show()
            },
            from.get(Calendar.YEAR),
            from.get(Calendar.MONTH),
            from.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun reloadFilter() {
        page = 0
        load()
    }

    private fun <T> multiChoice(
        title: String,
        values: List<T>,
        selected: Set<T>,
        onApply: (Set<T>) -> Unit
    ) {
        val labels = values.map { value ->
            when (value) {
                is SignalCategory -> value.label
                is SignalDirection -> value.label
                else -> value.toString()
            }
        }.toTypedArray()
        val checked = BooleanArray(values.size) { index ->
            values[index] in selected
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMultiChoiceItems(labels, checked) { _, index, isChecked ->
                checked[index] = isChecked
            }
            .setPositiveButton("Apply") { _, _ ->
                onApply(
                    values.filterIndexed { index, _ ->
                        checked[index]
                    }.toSet()
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun multiChoiceText(
        title: String,
        values: List<String>,
        selected: Set<String>,
        onApply: (Set<String>) -> Unit
    ) {
        val checked = BooleanArray(values.size) { index ->
            values[index] in selected
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMultiChoiceItems(
                values.toTypedArray(),
                checked
            ) { _, index, isChecked ->
                checked[index] = isChecked
            }
            .setPositiveButton("Apply") { _, _ ->
                onApply(
                    values.filterIndexed { index, _ ->
                        checked[index]
                    }.toSet()
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun textFilter(
        title: String,
        current: String,
        onApply: (String) -> Unit
    ) {
        val input = EditText(context).apply {
            hint = "Comma separated values"
            setSingleLine(true)
            setText(current)
            setTextColor(AppDesign.textPrimary)
            setHintTextColor(AppDesign.textMuted)
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                onApply(input.text?.toString().orEmpty())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun splitValues(value: String): Set<String> =
        value.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    private fun addMetricRow(
        parent: LinearLayout,
        vararg metrics: Pair<String, String>
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        metrics.forEach { (label, value) ->
            row.addView(metricCard(label, value))
        }
        parent.addView(row)
    }

    private fun metricCard(label: String, value: String): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AppDesign.cardBackground(context, 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            addView(
                AppDesign.text(
                    context,
                    label,
                    10f,
                    AppDesign.textMuted
                )
            )
            addView(
                AppDesign.text(
                    context,
                    value,
                    17f,
                    AppDesign.textPrimary,
                    bold = true
                )
            )
        }
    }

    private fun tableRow(
        values: List<String>,
        widths: IntArray,
        header: Boolean
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, dp(5))
            if (header) {
                setBackgroundColor(AppDesign.surfaceSolid)
            }
            values.forEachIndexed { index, value ->
                addView(
                    AppDesign.text(
                        context,
                        value,
                        if (header) 10f else 11f,
                        if (header) AppDesign.accent
                        else AppDesign.textPrimary,
                        bold = header
                    ).apply {
                        maxLines = 1
                        layoutParams = LinearLayout.LayoutParams(
                            dp(widths.getOrElse(index) { 80 }),
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 0, dp(6), 0)
                        }
                    }
                )
            }
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dp(1),
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            })
        }
    }

    private fun sectionLabel(value: String): TextView =
        AppDesign.text(
            context,
            value,
            17f,
            AppDesign.accent,
            bold = true
        ).apply {
            setPadding(0, dp(16), 0, dp(8))
        }

    private fun note(value: String): TextView =
        AppDesign.text(
            context,
            value,
            11f,
            AppDesign.textMuted
        ).apply {
            setPadding(0, dp(2), 0, dp(4))
        }

    private fun chip(
        label: String,
        onClick: () -> Unit
    ): TextView =
        AppDesign.text(
            context,
            label,
            11f,
            AppDesign.textPrimary,
            bold = true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = AppDesign.fieldBackground(context, 14)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, dp(6), 0)
            }
        }

    private fun button(
        label: String,
        onClick: () -> Unit
    ): TextView =
        AppDesign.text(
            context,
            label,
            11f,
            AppDesign.textPrimary,
            bold = true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = AppDesign.cardBackground(context, 12)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(dp(3), dp(4), dp(3), dp(4))
            }
        }

    private fun number(value: Double?): String =
        if (value == null || !value.isFinite()) {
            AnalyticsTime.NOT_AVAILABLE
        } else {
            String.format(java.util.Locale.US, "%.2f", value)
        }

    private fun percent(value: Double?): String =
        if (value == null || !value.isFinite()) {
            AnalyticsTime.NOT_AVAILABLE
        } else {
            String.format(java.util.Locale.US, "%.1f%%", value)
        }

    private fun dp(value: Int): Int = AppDesign.dp(context, value)

    companion object {
        fun show(
            context: Context,
            container: LinearLayout,
            onOpenChart: (String, String) -> Unit = { _, _ -> }
        ) {
            ResearchAnalyticsScreen(
                context = context,
                container = container,
                onOpenChart = onOpenChart
            ).create()
        }
    }
}
