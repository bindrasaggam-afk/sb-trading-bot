package com.sb.signals.presentation

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.sb.signals.analytics.IndicatorPerformanceAnalyzer
import com.sb.signals.analytics.PerformanceAnalyzer
import com.sb.signals.analytics.PerformanceSlice
import com.sb.signals.analytics.PerformanceStats
import com.sb.signals.domain.HoldingFilterGroup
import com.sb.signals.domain.HoldingPeriod
import com.sb.signals.domain.ResearchSignal
import com.sb.signals.domain.SignalCategory
import com.sb.signals.domain.SignalDirection
import com.sb.signals.domain.SignalFilter
import com.sb.signals.domain.SignalLifecycleStatus
import com.sb.signals.services.SignalsPlatform
import com.sb.ui.theme.AppDesign
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object SignalsScreen {

    fun show(
        context: Context,
        container: LinearLayout,
        onOpenChart: (String, String) -> Unit
    ) {
        SignalsController(context, container, onOpenChart).renderHome()
    }
}

private class SignalsController(
    private val context: Context,
    private val container: LinearLayout,
    private val onOpenChart: (String, String) -> Unit
) {

    private val repository = SignalsPlatform.repository(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat(
        "dd MMM yyyy HH:mm",
        Locale.US
    ).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }
    private val monthFormat = SimpleDateFormat(
        "MMM yyyy",
        Locale.US
    ).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }

    private var statusFilter: Set<SignalLifecycleStatus> = emptySet()
    private var directionFilter: SignalDirection? = null
    private var holdingFilter: HoldingFilterGroup = HoldingFilterGroup.ALL
    private var searchQuery: String = ""
    private var listRenderer: (() -> Unit)? = null
    private var requestToken = 0

    fun renderHome() {
        requestToken++
        listRenderer = null
        container.removeAllViews()
        addHeader(
            title = "Signals",
            subtitle = "Independent research engine, isolated from Paper Trading",
            onBack = null
        )
        addPlatformStatus()
        addPrimaryActions()

        addSection("Signal categories")
        addNavigationCard(
            "Intraday",
            "5m trend and breakout with 15m/1h confirmation. " +
                "Entry only during the session; signals expire at close.",
            AppDesign.accent
        ) {
            statusFilter = emptySet()
            directionFilter = null
            holdingFilter = HoldingFilterGroup.ALL
            searchQuery = ""
            renderList(
                category = SignalCategory.INTRADAY,
                title = "Intraday signals",
                back = ::renderHome
            )
        }
        addNavigationCard(
            "Swing",
            "Daily trend continuation with weekly confirmation. " +
                "Holding periods range from days to weeks.",
            AppDesign.positive
        ) {
            statusFilter = emptySet()
            directionFilter = null
            holdingFilter = HoldingFilterGroup.ALL
            searchQuery = ""
            renderList(
                category = SignalCategory.SWING,
                title = "Swing signals",
                back = ::renderHome
            )
        }
        addNavigationCard(
            "Delivery",
            "Positional trend signals for cash-market delivery. " +
                "Long-only, with configurable multi-month holding.",
            Color.rgb(96, 165, 250)
        ) {
            statusFilter = emptySet()
            directionFilter = null
            holdingFilter = HoldingFilterGroup.ALL
            searchQuery = ""
            renderList(
                category = SignalCategory.DELIVERY,
                title = "Delivery signals",
                back = ::renderHome
            )
        }

        addSection("Research")
        addNavigationCard(
            "Signal history",
            "All generated signals with lifecycle status and outcomes.",
            AppDesign.textMuted
        ) {
            statusFilter = emptySet()
            directionFilter = null
            holdingFilter = HoldingFilterGroup.ALL
            searchQuery = ""
            renderList(
                category = null,
                title = "Signal history",
                back = ::renderHome
            )
        }
        addNavigationCard(
            "Performance dashboard",
            "Win rate, expectancy, drawdown, category and holding analysis.",
            AppDesign.textMuted
        ) {
            renderResearch()
        }
        addNavigationCard(
            "Indicator research",
            "Compare confirmation filters against realised outcomes.",
            AppDesign.textMuted
        ) {
            renderIndicatorResearch()
        }
        addNavigationCard(
            "Strategy versions",
            "Immutable configuration snapshots used by generated signals.",
            AppDesign.textMuted
        ) {
            renderStrategyVersions()
        }
    }

    private fun addPlatformStatus() {
        val card = addCard()
        val lastRun = try {
            repository.getLastRun()
        } catch (_: Exception) {
            null
        }
        val running = SignalsPlatform.isRunning()
        addText(
            card,
            if (running) "BACKGROUND ANALYSIS ACTIVE" else "MANUAL RESEARCH MODE",
            11f,
            if (running) AppDesign.positive else AppDesign.accent,
            bold = true
        )
        addText(
            card,
            if (running) {
                "Signals refresh every five minutes when subscribed market data is available."
            } else {
                "Start analysis from this screen. No Paper Trading state is touched."
            },
            12f,
            AppDesign.textMuted
        )
        addText(
            card,
            if (lastRun != null) {
                "Last run: ${date(lastRun.finishedAt)}  |  " +
                    "symbols ${lastRun.symbolsEvaluated}  |  " +
                    "new ${lastRun.signalsGenerated}  |  " +
                    "evaluated ${lastRun.signalsEvaluated}  |  " +
                    "errors ${lastRun.errors}"
            } else {
                "No analysis run recorded yet."
            },
            11f,
            AppDesign.textMuted,
            topPadding = 6
        )
        if (lastRun != null && lastRun.message.isNotBlank()) {
            addText(
                card,
                lastRun.message,
                11f,
                if (lastRun.errors > 0) AppDesign.negative else AppDesign.textMuted,
                topPadding = 4
            )
        }
    }

    private fun addPrimaryActions() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 10, 0, 2) }
        }
        row.addView(
            actionButton("RUN ANALYSIS", true) {
                runAnalysis { renderHome() }
            },
            LinearLayout.LayoutParams(0, AppDesign.dp(context, 44), 1f)
        )
        row.addView(
            actionButton("REFRESH", false) { renderHome() },
            LinearLayout.LayoutParams(0, AppDesign.dp(context, 44), 1f).apply {
                marginStart = AppDesign.dp(context, 8)
            }
        )
        container.addView(row)
    }

    private fun renderList(
        category: SignalCategory?,
        title: String,
        back: () -> Unit
    ) {
        requestToken++
        val renderer = {
            renderList(category, title, back)
        }
        listRenderer = renderer
        container.removeAllViews()
        addHeader(
            title = title,
            subtitle = if (category == null) {
                "Every signal, including completed and expired studies"
            } else {
                "${category.label} signals are generated independently"
            },
            onBack = back
        )

        val controls = addCard()
        addText(
            controls,
            "LIFECYCLE FILTER",
            10f,
            AppDesign.textMuted,
            bold = true
        )
        addChipRow(
            parent = controls,
            options = listOf(
                "All" to emptySet(),
                "Open" to SignalLifecycleStatus.values()
                    .filter { it.isOpen }.toSet(),
                "New" to setOf(SignalLifecycleStatus.NEW),
                "Triggered" to setOf(SignalLifecycleStatus.TRIGGERED),
                "T1 hit" to setOf(SignalLifecycleStatus.TARGET_1_HIT),
                "T2 hit" to setOf(SignalLifecycleStatus.TARGET_2_HIT),
                "T3 hit" to setOf(SignalLifecycleStatus.TARGET_3_HIT),
                "Stopped" to setOf(SignalLifecycleStatus.STOPLOSS_HIT),
                "Expired" to setOf(SignalLifecycleStatus.EXPIRED)
            ),
            selected = statusFilter,
            onSelect = {
                statusFilter = it
                renderer()
            }
        )

        addText(
            controls,
            "DIRECTION",
            10f,
            AppDesign.textMuted,
            bold = true,
            topPadding = 8
        )
        addChipRow(
            parent = controls,
            options = listOf(
                "All" to null,
                "BUY" to SignalDirection.BUY,
                "SELL" to SignalDirection.SELL
            ),
            selected = directionFilter,
            onSelect = {
                directionFilter = it
                renderer()
            }
        )

        if (category == SignalCategory.SWING ||
            category == SignalCategory.DELIVERY
        ) {
            addText(
                controls,
                "HOLDING PERIOD",
                10f,
                AppDesign.textMuted,
                bold = true,
                topPadding = 8
            )
            addChipRow(
                parent = controls,
                options = HoldingFilterGroup.values().map {
                    it.label to it
                },
                selected = holdingFilter,
                onSelect = {
                    holdingFilter = it
                    renderer()
                }
            )
        }

        addText(
            controls,
            "SEARCH",
            10f,
            AppDesign.textMuted,
            bold = true,
            topPadding = 8
        )
        val searchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 5, 0, 0) }
        }
        val search = EditText(context).apply {
            hint = "Symbol or exchange"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(searchQuery)
            textSize = 12f
            setTextColor(AppDesign.textPrimary)
            setHintTextColor(AppDesign.textMuted)
            background = AppDesign.fieldBackground(context)
            setPadding(
                AppDesign.dp(context, 10),
                AppDesign.dp(context, 8),
                AppDesign.dp(context, 10),
                AppDesign.dp(context, 8)
            )
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        searchRow.addView(search)
        searchRow.addView(
            actionButton("APPLY", true) {
                searchQuery = search.text.toString()
                renderer()
            },
            LinearLayout.LayoutParams(
                AppDesign.dp(context, 78),
                AppDesign.dp(context, 40)
            ).apply { marginStart = AppDesign.dp(context, 6) }
        )
        if (searchQuery.isNotBlank()) {
            searchRow.addView(
                actionButton("CLEAR", false) {
                    searchQuery = ""
                    renderer()
                },
                LinearLayout.LayoutParams(
                    AppDesign.dp(context, 78),
                    AppDesign.dp(context, 40)
                ).apply { marginStart = AppDesign.dp(context, 6) }
            )
        }
        controls.addView(searchRow)

        val runRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 0) }
        }
        runRow.addView(
            actionButton("RUN ANALYSIS", true) {
                runAnalysis { renderer() }
            },
            LinearLayout.LayoutParams(
                0,
                AppDesign.dp(context, 40),
                1f
            )
        )
        controls.addView(runRow)

        val filter = SignalFilter(
            category = category,
            statuses = statusFilter,
            direction = directionFilter,
            holdingGroup = if (category == SignalCategory.SWING ||
                category == SignalCategory.DELIVERY
            ) {
                holdingFilter
            } else {
                HoldingFilterGroup.ALL
            },
            symbolQuery = searchQuery,
            limit = 200
        )
        val signals = try {
            repository.query(filter)
        } catch (_: Exception) {
            emptyList()
        }

        addText(
            container,
            "${signals.size} signal(s)",
            11f,
            AppDesign.textMuted,
            topPadding = 4
        )
        if (signals.isEmpty()) {
            addEmptyState(category)
            return
        }
        signals.forEach { signal ->
            addSignalCard(signal) {
                renderDetail(signal.id) {
                    renderer()
                }
            }
        }
    }

    private fun addEmptyState(category: SignalCategory?) {
        val card = addCard()
        addText(
            card,
            "No matching signals yet",
            14f,
            AppDesign.textPrimary,
            bold = true
        )
        addText(
            card,
            "Signals are generated from stored closed candles. " +
                "Open a chart or keep instruments in the active watchlist " +
                "so market history is available, then run analysis again.",
            12f,
            AppDesign.textMuted,
            topPadding = 5
        )
        if (category != null) {
            addText(
                card,
                "${category.label} uses only its own timeframe and strategy version.",
                11f,
                AppDesign.accent,
                topPadding = 5
            )
        }
    }

    private fun addSignalCard(
        signal: ResearchSignal,
        onClick: () -> Unit
    ) {
        val card = addCard()
        card.setOnClickListener { onClick() }

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            textView(
                "${signal.symbol}  ${signal.direction.label}",
                15f,
                directionColor(signal.direction),
                bold = true
            ),
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        top.addView(
            textView(
                signal.status.label.uppercase(Locale.ROOT),
                10f,
                statusColor(signal.status),
                bold = true
            )
        )
        card.addView(top)

        addText(
            card,
            "${signal.exchange}  |  ${signal.category.label}  |  " +
                "${signal.timeframe}  |  ${signal.holdingPeriod.label}",
            11f,
            AppDesign.textMuted,
            topPadding = 3
        )
        addText(
            card,
            "Entry ${price(signal.entryLow)}-${price(signal.entryHigh)}  " +
                "SL ${price(signal.stopLoss)}  T1 ${price(signal.target1)}",
            12f,
            AppDesign.textPrimary,
            topPadding = 6
        )
        addText(
            card,
            "Score ${number(signal.confidence, 1)}  |  " +
                "R:R ${number(signal.rewardRisk1, 2)}/${number(signal.rewardRisk2, 2)}/" +
                "${number(signal.rewardRisk3, 2)}  |  " +
                "DQ ${number(signal.dataQuality.score, 0)}",
            10f,
            AppDesign.textMuted,
            topPadding = 4
        )
        addText(
            card,
            "${signal.strategyId} v${signal.strategyVersion}  |  " +
                date(signal.generatedAt),
            10f,
            AppDesign.textMuted,
            topPadding = 2
        )
        if (signal.status.isCompleted &&
            signal.outcomeNetPnlPercent != null
        ) {
            addText(
                card,
                "Net ${percent(signal.outcomeNetPnlPercent)}  |  " +
                    "R ${signedNumber(signal.outcomeR, 2)}  |  " +
                    (signal.outcomeReason ?: signal.status.label),
                11f,
                pnlColor(signal.outcomeNetPnlPercent),
                bold = true,
                topPadding = 5
            )
        }
    }

    private fun renderDetail(signalId: Long, back: () -> Unit) {
        requestToken++
        val signal = try {
            repository.getById(signalId)
        } catch (_: Exception) {
            null
        }
        container.removeAllViews()
        if (signal == null) {
            addHeader("Signal detail", "Signal no longer available", back)
            addText(
                container,
                "The record could not be loaded.",
                12f,
                AppDesign.textMuted
            )
            return
        }

        addHeader(
            title = "${signal.symbol} ${signal.direction.label}",
            subtitle = "${signal.category.label}  |  ${signal.status.label}  |  " +
                "${signal.strategyId} v${signal.strategyVersion}",
            onBack = back
        )

        val summary = addCard()
        addText(
            summary,
            "${signal.direction.label}  ${signal.status.label.uppercase(Locale.ROOT)}",
            18f,
            directionColor(signal.direction),
            bold = true
        )
        addText(
            summary,
            "${signal.exchange}  |  ${signal.timeframe}  |  " +
                "confidence ${number(signal.confidence, 1)}/100  |  " +
                "data quality ${number(signal.dataQuality.score, 0)}/100",
            12f,
            AppDesign.textMuted,
            topPadding = 4
        )
        addDivider(summary)
        addKeyValue(summary, "Reference", price(signal.referencePrice))
        addKeyValue(
            summary,
            "Entry zone",
            "${price(signal.entryLow)} - ${price(signal.entryHigh)}"
        )
        addKeyValue(summary, "Stop-loss", price(signal.stopLoss))
        addKeyValue(
            summary,
            "Targets",
            "T1 ${price(signal.target1)}   T2 ${price(signal.target2)}   " +
                "T3 ${price(signal.target3)}"
        )
        addKeyValue(
            summary,
            "Risk / rewards",
            "1R ${price(signal.riskPerUnit)}  |  " +
                "${number(signal.rewardRisk1, 2)}R / " +
                "${number(signal.rewardRisk2, 2)}R / " +
                "${number(signal.rewardRisk3, 2)}R"
        )
        addKeyValue(
            summary,
            "Holding",
            "${signal.holdingPeriod.label}  |  expires ${date(signal.expiresAt)}"
        )
        addKeyValue(summary, "Generated", date(signal.generatedAt))
        addKeyValue(
            summary,
            "Candle",
            "${date(signal.candleTimestamp)}  |  ${signal.timeframe}"
        )

        if (signal.status.isCompleted) {
            addSection("Outcome")
            val outcome = addCard()
            addKeyValue(
                outcome,
                "Status",
                signal.status.label,
                statusColor(signal.status)
            )
            addKeyValue(
                outcome,
                "Exit",
                price(signal.exitPrice)
            )
            addKeyValue(
                outcome,
                "Gross / net",
                "${percent(signal.outcomePnlPercent)} / " +
                    percent(signal.outcomeNetPnlPercent),
                pnlColor(signal.outcomeNetPnlPercent)
            )
            addKeyValue(
                outcome,
                "R multiple",
                signedNumber(signal.outcomeR, 2),
                pnlColor(signal.outcomeNetPnlPercent)
            )
            addKeyValue(
                outcome,
                "Costs",
                percent(signal.outcomeCostPercent)
            )
            addKeyValue(
                outcome,
                "MFE / MAE",
                "${percent(signal.maxFavorableExcursionPercent)} / " +
                    percent(signal.maxAdverseExcursionPercent)
            )
            addKeyValue(
                outcome,
                "Reason",
                signal.outcomeReason ?: "-"
            )
            if (signal.ambiguousBar) {
                addText(
                    outcome,
                    "Ambiguous bar: stop and target were both touched. " +
                        "Outcome uses the conservative stop-first assumption.",
                    11f,
                    AppDesign.negative,
                    topPadding = 6
                )
            }
        }

        addSection("Why this signal")
        val reasons = addCard()
        if (signal.reasons.isEmpty()) {
            addText(reasons, "No reasons recorded.", 12f, AppDesign.textMuted)
        } else {
            signal.reasons.forEach {
                addText(reasons, "- $it", 12f, AppDesign.textPrimary, topPadding = 3)
            }
        }

        addSection("Indicator confirmations")
        addConfirmationCard(signal)

        addSection("Indicator snapshot")
        addSnapshotCard(signal)

        addSection("Data quality")
        val quality = addCard()
        addKeyValue(
            quality,
            "Score",
            "${number(signal.dataQuality.score, 0)}/100"
        )
        addKeyValue(
            quality,
            "Closed candles",
            signal.dataQuality.candleCount.toString()
        )
        if (signal.dataQuality.notes.isEmpty()) {
            addText(
                quality,
                "No quality warnings.",
                11f,
                AppDesign.positive,
                topPadding = 4
            )
        } else {
            signal.dataQuality.notes.forEach {
                addText(quality, "- $it", 11f, AppDesign.textMuted, topPadding = 3)
            }
        }

        addSection("Lifecycle timeline")
        val timeline = addCard()
        val events = try {
            repository.getEvents(signal.id)
        } catch (_: Exception) {
            emptyList()
        }
        if (events.isEmpty()) {
            addText(timeline, "No lifecycle events.", 12f, AppDesign.textMuted)
        } else {
            events.forEach { event ->
                addText(
                    timeline,
                    "${date(event.eventTime)}  ${event.toStatus.label}",
                    11f,
                    statusColor(event.toStatus),
                    bold = true,
                    topPadding = 4
                )
                if (event.message.isNotBlank()) {
                    addText(
                        timeline,
                        event.message,
                        11f,
                        AppDesign.textMuted
                    )
                }
            }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
        }
        row.addView(
            actionButton("OPEN CHART", true) {
                onOpenChart(signal.symbol, signal.exchange)
            },
            LinearLayout.LayoutParams(0, AppDesign.dp(context, 44), 1f)
        )
        row.addView(
            actionButton("BACK", false) { back() },
            LinearLayout.LayoutParams(0, AppDesign.dp(context, 44), 1f).apply {
                marginStart = AppDesign.dp(context, 8)
            }
        )
        container.addView(row)
    }

    private fun addConfirmationCard(signal: ResearchSignal) {
        val card = addCard()
        val confirmations = signal.indicatorSnapshot.confirmations
        if (confirmations.isEmpty()) {
            addText(card, "No confirmations stored.", 12f, AppDesign.textMuted)
            return
        }
        confirmations.forEach { (name, confirmed) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 3, 0, 0) }
            }
            row.addView(
                textView(name, 12f, AppDesign.textPrimary),
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
            row.addView(
                textView(
                    if (confirmed) "CONFIRMED" else "NOT CONFIRMED",
                    10f,
                    if (confirmed) AppDesign.positive else AppDesign.textMuted,
                    bold = true
                )
            )
            card.addView(row)
        }
    }

    private fun addSnapshotCard(signal: ResearchSignal) {
        val card = addCard()
        val values = signal.indicatorSnapshot.values
        if (values.isEmpty()) {
            addText(card, "No indicator values stored.", 12f, AppDesign.textMuted)
            return
        }
        values.entries.sortedBy { it.key }.forEach { (key, value) ->
            addKeyValue(
                card,
                key,
                if (value == null) "-" else number(value, 4),
                size = 10f
            )
        }
    }

    private fun renderResearch() {
        val token = ++requestToken
        listRenderer = null
        container.removeAllViews()
        addHeader(
            title = "Performance dashboard",
            subtitle = "Completed outcomes, cost-aware and conservatively evaluated",
            onBack = ::renderHome
        )
        addLoading("Computing research statistics...")

        Thread {
            val result = try {
                val signals = repository.query(
                    SignalFilter(limit = 1000)
                )
                PerformanceAnalyzer.analyze(signals)
            } catch (_: Exception) {
                null
            }
            mainHandler.post {
                if (token != requestToken) return@post
                container.removeAllViews()
                addHeader(
                    title = "Performance dashboard",
                    subtitle = "Completed outcomes, cost-aware and conservatively evaluated",
                    onBack = ::renderHome
                )
                if (result == null) {
                    addText(
                        container,
                        "Research statistics could not be loaded.",
                        12f,
                        AppDesign.negative
                    )
                    return@post
                }
                addPerformanceSummary(result)
                addSliceSection(
                    "By category",
                    result.byCategory
                )
                addSliceSection(
                    "By strategy",
                    result.byStrategy
                )
                addSliceSection(
                    "By holding period",
                    result.byHoldingPeriod
                )
                addSliceSection(
                    "By direction",
                    result.byDirection
                )
                addSliceSection(
                    "By volatility",
                    result.byVolatility
                )
                addSliceSection(
                    "By data quality",
                    result.byDataQuality
                )
                addSliceSection(
                    "By higher-timeframe trend",
                    result.byHigherTimeframeTrend
                )
                addSliceSection(
                    "Monthly report",
                    result.byMonth
                )
                addMethodologyNote()
                addNavigationCard(
                    "Indicator research",
                    "Open the confirmation-by-confirmation breakdown.",
                    AppDesign.textMuted
                ) {
                    renderIndicatorResearch()
                }
            }
        }.start()
    }

    private fun addPerformanceSummary(stats: PerformanceStats) {
        val card = addCard()
        addText(
            card,
            "RESEARCH SUMMARY",
            11f,
            AppDesign.accent,
            bold = true
        )
        addStatRow(
            card,
            "Generated / completed",
            "${stats.generated} / ${stats.completed}"
        )
        addStatRow(
            card,
            "Evaluated trades",
            "${stats.evaluated}  (open ${stats.open}, invalidated ${stats.invalidated})"
        )
        addStatRow(
            card,
            "Win / loss / flat",
            "${stats.wins} / ${stats.losses} / ${stats.breakeven}"
        )
        addStatRow(
            card,
            "Win rate",
            "${number(stats.winRatePct, 1)}%",
            if (stats.winRatePct >= 50.0) AppDesign.positive else AppDesign.accent
        )
        addStatRow(
            card,
            "Profit factor",
            if (stats.profitFactor.isInfinite()) {
                "No losing trades"
            } else {
                number(stats.profitFactor, 2)
            },
            if (stats.profitFactor >= 1.0 || stats.profitFactor.isInfinite()) {
                AppDesign.positive
            } else {
                AppDesign.negative
            }
        )
        addStatRow(
            card,
            "Average win / loss",
            "${percent(stats.avgWinPct)} / ${percent(stats.avgLossPct)}"
        )
        addStatRow(
            card,
            "Average net / expectancy",
            "${percent(stats.avgNetPnlPct)} / " +
                "${signedNumber(stats.expectancyR, 2)}R"
        )
        addStatRow(
            card,
            "Max drawdown",
            "${number(stats.maxDrawdownPct, 2)}%",
            AppDesign.negative
        )
        addStatRow(
            card,
            "Max consecutive losses",
            stats.maxConsecutiveLosses.toString()
        )
        addStatRow(
            card,
            "Completion rate",
            "${number(stats.completionRatePct, 1)}%"
        )
    }

    private fun addSliceSection(
        title: String,
        slices: Map<String, PerformanceSlice>
    ) {
        val relevant = slices.entries.filter {
            it.key != "ALL" && it.value.total > 0
        }
        if (relevant.isEmpty()) return

        addSection(title)
        val card = addCard()
        relevant.forEach { (label, slice) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, AppDesign.dp(context, 5), 0, 0)
            }
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            top.addView(
                textView(label, 12f, AppDesign.textPrimary, bold = true),
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
            top.addView(
                textView(
                    "${slice.wins}/${slice.total} wins",
                    11f,
                    if (slice.winRatePct >= 50.0) {
                        AppDesign.positive
                    } else {
                        AppDesign.negative
                    },
                    bold = true
                )
            )
            row.addView(top)
            row.addView(
                textView(
                    "${number(slice.winRatePct, 1)}% win rate  |  " +
                        "avg ${percent(slice.avgNetPnlPct)}  |  " +
                        "avg ${signedNumber(slice.avgR, 2)}R",
                    10f,
                    AppDesign.textMuted
                )
            )
            card.addView(row)
            card.addView(divider())
        }
    }

    private fun addMethodologyNote() {
        addSection("Methodology")
        val card = addCard()
        addText(
            card,
            "Completed outcomes include target, stop, time expiry and closed signals. " +
                "Expired signals without an entry are stored but excluded from win-rate " +
                "and expectancy denominators.",
            11f,
            AppDesign.textMuted
        )
        addText(
            card,
            "When a candle touches both stop and target, the stop is assumed first. " +
                "Net return subtracts round-trip brokerage and slippage from the " +
                "strategy configuration.",
            11f,
            AppDesign.textMuted,
            topPadding = 5
        )
        addText(
            card,
            "The dashboard is research evidence only. It does not place orders or " +
                "modify Paper Trading balances.",
            11f,
            AppDesign.accent,
            topPadding = 5
        )
    }

    private fun renderIndicatorResearch() {
        val token = ++requestToken
        listRenderer = null
        container.removeAllViews()
        addHeader(
            title = "Indicator research",
            subtitle = "Outcome statistics for each confirmation condition",
            onBack = ::renderResearch
        )
        addLoading("Comparing indicator confirmations...")

        Thread {
            val result = try {
                IndicatorPerformanceAnalyzer.analyze(
                    repository.query(SignalFilter(limit = 1000))
                )
            } catch (_: Exception) {
                null
            }
            mainHandler.post {
                if (token != requestToken) return@post
                container.removeAllViews()
                addHeader(
                    title = "Indicator research",
                    subtitle = "Outcome statistics for each confirmation condition",
                    onBack = ::renderResearch
                )
                if (result == null) {
                    addText(
                        container,
                        "Indicator research could not be loaded.",
                        12f,
                        AppDesign.negative
                    )
                    return@post
                }
                if (result.isEmpty()) {
                    val card = addCard()
                    addText(
                        card,
                        "No completed outcomes yet.",
                        13f,
                        AppDesign.textPrimary,
                        bold = true
                    )
                    addText(
                        card,
                        "Generate signals, then wait for targets, stops or expiry. " +
                            "Every completed signal stores its full confirmation snapshot.",
                        12f,
                        AppDesign.textMuted,
                        topPadding = 4
                    )
                    return@post
                }

                val card = addCard()
                addText(
                    card,
                    "CONFIRMATION PERFORMANCE",
                    11f,
                    AppDesign.accent,
                    bold = true
                )
                result.forEach { indicator ->
                    addText(
                        card,
                        indicator.indicator,
                        13f,
                        AppDesign.textPrimary,
                        bold = true,
                        topPadding = 8
                    )
                    addText(
                        card,
                        "${indicator.wins}/${indicator.confirmedSignals} wins  |  " +
                            "${number(indicator.winRatePct, 1)}%  |  " +
                            "avg ${percent(indicator.avgNetPnlPct)}  |  " +
                            "avg ${signedNumber(indicator.avgR, 2)}R",
                        11f,
                        if (indicator.winRatePct >= 50.0) {
                            AppDesign.positive
                        } else {
                            AppDesign.negative
                        }
                    )
                    card.addView(divider().apply {
                        setPadding(0, AppDesign.dp(context, 5), 0, 0)
                    })
                }
                addText(
                    container,
                    "This compares only signals where the confirmation was true. " +
                        "Small samples can be misleading.",
                    10f,
                    AppDesign.textMuted,
                    topPadding = 4
                )
            }
        }.start()
    }

    private fun renderStrategyVersions() {
        requestToken++
        listRenderer = null
        container.removeAllViews()
        addHeader(
            title = "Strategy versions",
            subtitle = "Immutable configurations attached to every signal",
            onBack = ::renderHome
        )
        val versions = try {
            repository.getStrategyVersions()
        } catch (_: Exception) {
            emptyList()
        }
        if (versions.isEmpty()) {
            addText(
                container,
                "No strategy versions registered. Run analysis to seed defaults.",
                12f,
                AppDesign.textMuted
            )
            return
        }
        versions.forEach { version ->
            val card = addCard()
            addText(
                card,
                "${version.category.label}  ${version.name}",
                14f,
                AppDesign.textPrimary,
                bold = true
            )
            addText(
                card,
                "${version.strategyId} v${version.version}  |  " +
                    (if (version.active) "ACTIVE" else "INACTIVE"),
                11f,
                if (version.active) AppDesign.positive else AppDesign.textMuted,
                bold = true,
                topPadding = 3
            )
            addText(
                card,
                version.createdAt.let { date(it) },
                10f,
                AppDesign.textMuted,
                topPadding = 2
            )
            val config = try {
                JSONObject(version.configJson)
            } catch (_: Exception) {
                null
            }
            if (config != null) {
                addText(
                    card,
                    "min score ${config.optDouble("minScore", 0.0)}  |  " +
                        "min ADX ${config.optDouble("minAdx", 0.0)}  |  " +
                        "stop ${config.optDouble("stopAtrMultiplier", 0.0)} ATR  |  " +
                        "targets ${config.optDouble("targetR1", 0.0)}/" +
                        "${config.optDouble("targetR2", 0.0)}/" +
                        "${config.optDouble("targetR3", 0.0)}R",
                    10f,
                    AppDesign.textMuted,
                    topPadding = 4
                )
                addText(
                    card,
                    "costs ${config.optDouble("costBpsPerSide", 0.0)} bps + " +
                        "${config.optDouble("slippageBpsPerSide", 0.0)} bps/side  |  " +
                        "volume ${config.optDouble("volumeMultiplier", 0.0)}x",
                    10f,
                    AppDesign.textMuted,
                    topPadding = 2
                )
            }
            if (version.notes.isNotBlank()) {
                addText(
                    card,
                    version.notes,
                    11f,
                    AppDesign.textMuted,
                    topPadding = 4
                )
            }
        }
    }

    private fun runAnalysis(onComplete: () -> Unit) {
        Toast.makeText(
            context,
            "Signal analysis queued",
            Toast.LENGTH_SHORT
        ).show()
        val started = SignalsPlatform.analyzeNow(context) { summary ->
            mainHandler.post {
                if (summary == null) {
                    Toast.makeText(
                        context,
                        "Signal analysis could not start",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "Generated ${summary.signalsGenerated}, " +
                            "evaluated ${summary.signalsEvaluated}" +
                            if (summary.errors > 0) {
                                ", errors ${summary.errors}"
                            } else {
                                ""
                            },
                        Toast.LENGTH_LONG
                    ).show()
                }
                onComplete()
            }
        }
        if (!started) {
            Toast.makeText(
                context,
                "Signal analysis is unavailable",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun addHeader(
        title: String,
        subtitle: String,
        onBack: (() -> Unit)?
    ) {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        if (onBack != null) {
            val back = textView("<", 24f, AppDesign.accent, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(
                    AppDesign.dp(context, 4),
                    0,
                    AppDesign.dp(context, 12),
                    0
                )
                setOnClickListener { onBack() }
            }
            header.addView(
                back,
                LinearLayout.LayoutParams(
                    AppDesign.dp(context, 32),
                    AppDesign.dp(context, 40)
                )
            )
        }
        val titleColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        titleColumn.addView(textView(title, 21f, AppDesign.textPrimary, bold = true))
        titleColumn.addView(
            textView(subtitle, 11f, AppDesign.textMuted).apply {
                setPadding(0, AppDesign.dp(context, 3), 0, 0)
            }
        )
        header.addView(titleColumn)
        container.addView(header)
        container.addView(divider().apply {
            setPadding(0, AppDesign.dp(context, 10), 0, 0)
        })
    }

    private fun addSection(title: String) {
        addText(
            container,
            title.uppercase(Locale.ROOT),
            10f,
            AppDesign.textMuted,
            bold = true,
            topPadding = 14
        )
    }

    private fun addNavigationCard(
        title: String,
        subtitle: String,
        accent: Int,
        onClick: () -> Unit
    ) {
        val card = addCard()
        card.setOnClickListener { onClick() }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            View(context).apply {
                setBackgroundColor(accent)
                layoutParams = LinearLayout.LayoutParams(
                    AppDesign.dp(context, 3),
                    AppDesign.dp(context, 34)
                )
            }
        )
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                AppDesign.dp(context, 10),
                0,
                0,
                0
            )
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        column.addView(textView(title, 14f, AppDesign.textPrimary, bold = true))
        column.addView(
            textView(subtitle, 11f, AppDesign.textMuted).apply {
                setPadding(0, AppDesign.dp(context, 3), 0, 0)
            }
        )
        row.addView(column)
        row.addView(textView(">", 18f, accent, bold = true))
        card.addView(row)
    }

    private fun addCard(): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AppDesign.cardBackground(context)
            setPadding(
                AppDesign.dp(context, 12),
                AppDesign.dp(context, 11),
                AppDesign.dp(context, 12),
                AppDesign.dp(context, 11)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, AppDesign.dp(context, 8)) }
        }
        container.addView(card)
        return card
    }

    private fun addText(
        parent: LinearLayout,
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        topPadding: Int = 0
    ) {
        parent.addView(
            textView(value, size, color, bold).apply {
                if (topPadding > 0) {
                    setPadding(0, AppDesign.dp(context, topPadding), 0, 0)
                }
            }
        )
    }

    private fun addKeyValue(
        parent: LinearLayout,
        key: String,
        value: String,
        valueColor: Int = AppDesign.textPrimary,
        size: Float = 11f
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, AppDesign.dp(context, 3), 0, 0)
        }
        row.addView(
            textView(key, size, AppDesign.textMuted),
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0.45f
            )
        )
        row.addView(
            textView(value, size, valueColor, bold = true),
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0.55f
            )
        )
        parent.addView(row)
    }

    private fun addStatRow(
        parent: LinearLayout,
        key: String,
        value: String,
        valueColor: Int = AppDesign.textPrimary
    ) {
        addKeyValue(parent, key, value, valueColor, 12f)
    }

    private fun addDivider(parent: LinearLayout) {
        parent.addView(divider().apply {
            setPadding(0, AppDesign.dp(context, 7), 0, AppDesign.dp(context, 3))
        })
    }

    private fun divider(): View {
        return View(context).apply {
            setBackgroundColor(AppDesign.divider)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
            )
        }
    }

    private fun <T> addChipRow(
        parent: LinearLayout,
        options: List<Pair<String, T>>,
        selected: T,
        onSelect: (T) -> Unit
    ) {
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, AppDesign.dp(context, 5), 0, 0) }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        options.forEach { (label, value) ->
            val isSelected = selected == value
            val chip = textView(
                label,
                10f,
                if (isSelected) AppDesign.background else AppDesign.textPrimary,
                bold = true
            ).apply {
                gravity = Gravity.CENTER
                background = AppDesign.roundedBackground(
                    context,
                    if (isSelected) AppDesign.accent else AppDesign.field,
                    14
                )
                setPadding(
                    AppDesign.dp(context, 11),
                    AppDesign.dp(context, 7),
                    AppDesign.dp(context, 11),
                    AppDesign.dp(context, 7)
                )
                setOnClickListener { onSelect(value) }
            }
            row.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                AppDesign.dp(context, 32)
            ).apply { marginEnd = AppDesign.dp(context, 6) })
        }
        scroll.addView(row)
        parent.addView(scroll)
    }

    private fun actionButton(
        label: String,
        primary: Boolean,
        onClick: () -> Unit
    ): TextView {
        return textView(
            label,
            10f,
            if (primary) AppDesign.background else AppDesign.textPrimary,
            bold = true
        ).apply {
            gravity = Gravity.CENTER
            background = AppDesign.roundedBackground(
                context,
                if (primary) AppDesign.accent else AppDesign.field,
                9
            )
            setPadding(
                AppDesign.dp(context, 10),
                AppDesign.dp(context, 8),
                AppDesign.dp(context, 10),
                AppDesign.dp(context, 8)
            )
            setOnClickListener { onClick() }
        }
    }

    private fun textView(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView {
        return AppDesign.text(
            context,
            value,
            size,
            color,
            bold = bold
        ).apply {
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun addLoading(message: String) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, AppDesign.dp(context, 16), 0, 0)
        }
        row.addView(
            ProgressBar(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    AppDesign.dp(context, 24),
                    AppDesign.dp(context, 24)
                )
            }
        )
        row.addView(
            textView(message, 12f, AppDesign.textMuted).apply {
                setPadding(AppDesign.dp(context, 10), 0, 0, 0)
            }
        )
        container.addView(row)
    }

    private fun directionColor(direction: SignalDirection): Int =
        if (direction == SignalDirection.BUY) {
            AppDesign.positive
        } else {
            AppDesign.negative
        }

    private fun pnlColor(value: Double?): Int {
        if (value == null) return AppDesign.textMuted
        return when {
            value > 0.001 -> AppDesign.positive
            value < -0.001 -> AppDesign.negative
            else -> AppDesign.textMuted
        }
    }

    private fun statusColor(status: SignalLifecycleStatus): Int {
        return when (status) {
            SignalLifecycleStatus.NEW,
            SignalLifecycleStatus.ACTIVE,
            SignalLifecycleStatus.TRIGGERED -> AppDesign.accent
            SignalLifecycleStatus.TARGET_1_HIT,
            SignalLifecycleStatus.TARGET_2_HIT,
            SignalLifecycleStatus.TARGET_3_HIT -> AppDesign.positive
            SignalLifecycleStatus.STOPLOSS_HIT -> AppDesign.negative
            SignalLifecycleStatus.EXPIRED -> AppDesign.textMuted
            SignalLifecycleStatus.INVALIDATED -> AppDesign.negative
            SignalLifecycleStatus.CLOSED -> AppDesign.textPrimary
        }
    }

    private fun price(value: Double?): String {
        if (value == null || !value.isFinite()) return "-"
        return String.format(Locale.US, "%,.2f", value)
    }

    private fun number(value: Double?, decimals: Int = 2): String {
        if (value == null || !value.isFinite()) return "-"
        return String.format(Locale.US, "%.${decimals}f", value)
    }

    private fun signedNumber(value: Double?, decimals: Int = 2): String {
        if (value == null || !value.isFinite()) return "-"
        return String.format(Locale.US, "%+.${decimals}f", value)
    }

    private fun percent(value: Double?): String {
        if (value == null || !value.isFinite()) return "-"
        return String.format(Locale.US, "%+.2f%%", value)
    }

    private fun date(timestamp: Long): String {
        return dateFormat.format(Date(timestamp))
    }
}
