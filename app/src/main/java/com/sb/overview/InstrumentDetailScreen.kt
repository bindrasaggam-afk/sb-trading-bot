package com.sb.overview

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.sb.backend.core.BackendManager
import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.market.model.MarketTick
import com.sb.backend.market.repo.MarketFeedRepository
import com.sb.backend.models.Candle
import com.sb.backend.models.Instrument
import com.sb.backend.models.Quote
import com.sb.ui.theme.AppDesign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class InstrumentDetailScreen(
    private val context: Context,
    exchange: String,
    symbol: String,
    private val scope: CoroutineScope,
    private val onBack: () -> Unit,
    private val onOpenChart: (exchange: String, symbol: String) -> Unit
) {

    private val key = InstrumentKey(
        exchange = exchange.trim().uppercase(Locale.ROOT),
        symbol = symbol.trim()
    )

    private var loadJob: Job? = null
    private var liveJob: Job? = null
    private var instrument: Instrument? = null
    private var dailyCandles: List<Candle> = emptyList()
    private var snapshot: OverviewSnapshot? = null
    private var selectedRange = "3M"
    private var released = false
    private var lastFullRenderAt = 0L

    private lateinit var content: LinearLayout
    private lateinit var priceText: TextView
    private lateinit var changeText: TextView
    private lateinit var stateText: TextView
    private lateinit var updatedText: TextView
    private lateinit var nameText: TextView
    private lateinit var symbolText: TextView
    private lateinit var metaText: TextView
    private lateinit var chartView: OverviewLineChartView
    private val rangeChips = LinkedHashMap<String, TextView>()

    private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US)
    private val dayFormat = SimpleDateFormat("dd MMM yy", Locale.US)

    fun createView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppDesign.background)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(createTopBar())

        val scroll = ScrollView(context).apply {
            isFillViewport = true
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(22))
        }
        scroll.addView(body)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        body.addView(createIdentityBlock())
        body.addView(createTabs())
        body.addView(createPriceCard())

        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(content)

        content.addView(
            AppDesign.text(
                context,
                "Loading Overview from stored daily candles...",
                12f,
                AppDesign.textMuted
            ).apply { setPadding(0, dp(14), 0, dp(8)) }
        )

        loadOverview()
        startLiveUpdates()
        return root
    }

    fun release() {
        if (released) return
        released = true
        loadJob?.cancel()
        loadJob = null
        liveJob?.cancel()
        liveJob = null
        MarketFeedRepository.unsubscribe(key)
    }

    private fun createTopBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(2))
        }

        bar.addView(
            AppDesign.text(context, "‹", 26f, AppDesign.accent, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(2), 0, dp(10), dp(2))
                setOnClickListener { onBack() }
            }
        )

        bar.addView(
            AppDesign.text(
                context,
                "INSTRUMENT DETAIL",
                12f,
                AppDesign.accent,
                bold = true
            ),
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        bar.addView(
            AppDesign.text(
                context,
                "CHART",
                10f,
                AppDesign.textPrimary,
                bold = true
            ).apply {
                gravity = Gravity.CENTER
                background = AppDesign.fieldBackground(context, 8)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                setOnClickListener {
                    onOpenChart(key.exchange, key.symbol)
                }
            }
        )

        return bar
    }

    private fun createIdentityBlock(): View {
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(6))
        }

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        symbolText = AppDesign.text(
            context,
            key.symbol,
            20f,
            AppDesign.textPrimary,
            bold = true
        )
        top.addView(
            symbolText,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        top.addView(
            AppDesign.text(
                context,
                key.exchange,
                10f,
                Color.BLACK,
                bold = true
            ).apply {
                gravity = Gravity.CENTER
                background = AppDesign.roundedBackground(
                    context,
                    AppDesign.accent,
                    7
                )
                setPadding(dp(7), dp(3), dp(7), dp(3))
            }
        )
        block.addView(top)

        nameText = AppDesign.text(
            context,
            "",
            12f,
            AppDesign.textPrimary
        ).apply {
            setPadding(0, dp(2), 0, 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        block.addView(nameText)

        metaText = AppDesign.text(
            context,
            "",
            10f,
            AppDesign.textMuted
        ).apply { setPadding(0, dp(2), 0, 0) }
        block.addView(metaText)

        return block
    }

    private fun createTabs(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(6))
        }

        listOf("Overview", "News", "Meetings", "Voting").forEach { tab ->
            val active = tab == "Overview"
            val view = AppDesign.text(
                context,
                tab,
                11f,
                if (active) Color.BLACK else AppDesign.textMuted,
                bold = active
            ).apply {
                gravity = Gravity.CENTER
                background = AppDesign.roundedBackground(
                    context,
                    if (active) AppDesign.accent else AppDesign.surface,
                    8
                )
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener {
                    if (!active) {
                        Toast.makeText(
                            context,
                            "$tab section will be added in a later phase.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            row.addView(
                view,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { setMargins(0, 0, dp(5), 0) }
            )
        }

        return row
    }

    private fun createPriceCard(): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AppDesign.cardBackground(context, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }

        val priceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        priceText = AppDesign.text(
            context,
            "—",
            26f,
            AppDesign.textPrimary,
            bold = true
        )
        priceRow.addView(
            priceText,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        stateText = AppDesign.text(
            context,
            "LOADING",
            9f,
            Color.BLACK,
            bold = true
        ).apply {
            gravity = Gravity.CENTER
            background = AppDesign.roundedBackground(
                context,
                AppDesign.textMuted,
                7
            )
            setPadding(dp(7), dp(4), dp(7), dp(4))
        }
        priceRow.addView(stateText)
        card.addView(priceRow)

        changeText = AppDesign.text(
            context,
            "Waiting for quote",
            12f,
            AppDesign.textMuted,
            bold = true
        ).apply { setPadding(0, dp(2), 0, 0) }
        card.addView(changeText)

        updatedText = AppDesign.text(
            context,
            "",
            10f,
            AppDesign.textMuted
        ).apply { setPadding(0, dp(3), 0, 0) }
        card.addView(updatedText)

        return card
    }

    private fun loadOverview() {
        loadJob?.cancel()
        loadJob = scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    if (!BackendManager.isInitialized()) {
                        return@withContext LoadedOverview(
                            error = "Backend is still initializing. Open this screen again shortly."
                        )
                    }

                    val instruments = BackendManager.instruments()
                    val instrument = instruments.findBySymbol(
                        key.symbol,
                        key.exchange
                    ) ?: return@withContext LoadedOverview(
                        error = "Instrument not found in the local master for " +
                            "${key.exchange}:${key.symbol}."
                    )

                    val market = BackendManager.market()
                    val candles = market.getCandles(
                        symbol = instrument.symbol,
                        timeframe = "1D",
                        exchange = instrument.exchange,
                        limit = 600
                    )

                    val storedQuote = market.getQuote(
                        instrument.symbol,
                        instrument.exchange
                    )
                    val liveTick = MarketFeedRepository.getLatestTick(key)
                    val quote = liveTick?.let(::tickToQuote) ?: storedQuote

                    LoadedOverview(
                        instrument = instrument,
                        candles = candles,
                        snapshot = OverviewCalculator.calculate(
                            candles = candles,
                            quote = quote,
                            now = System.currentTimeMillis()
                        )
                    )
                } catch (error: Exception) {
                    LoadedOverview(
                        error = "Overview load failed: " +
                            (error.message ?: "unknown error")
                    )
                }
            }

            if (released) return@launch

            val error = loaded.error
            if (error != null) {
                content.removeAllViews()
                content.addView(
                    AppDesign.text(
                        context,
                        error,
                        12f,
                        AppDesign.negative
                    ).apply { setPadding(0, dp(14), 0, dp(8)) }
                )
                stateText.text = "NOT AVAILABLE"
                stateText.background = AppDesign.roundedBackground(
                    context,
                    AppDesign.negative,
                    7
                )
                updatedText.text = "No reliable local data available."
                return@launch
            }

            val loadedInstrument = loaded.instrument
            val loadedSnapshot = loaded.snapshot
            if (loadedInstrument == null || loadedSnapshot == null) {
                content.removeAllViews()
                content.addView(
                    AppDesign.text(
                        context,
                        "Overview data is incomplete.",
                        12f,
                        AppDesign.negative
                    ).apply { setPadding(0, dp(14), 0, dp(8)) }
                )
                stateText.text = "NOT AVAILABLE"
                return@launch
            }

            instrument = loadedInstrument
            dailyCandles = loaded.candles
            snapshot = loadedSnapshot
            bindIdentity(loadedInstrument)
            applySnapshot(loadedSnapshot, full = true)
        }
    }

    private fun startLiveUpdates() {
        MarketFeedRepository.subscribe(key)
        liveJob?.cancel()
        liveJob = scope.launch {
            MarketFeedRepository.observeTick(key).collect { tick ->
                if (released || instrument == null ||
                    tick == null || tick.ltp <= 0.0
                ) {
                    return@collect
                }

                val currentCandles = dailyCandles
                val next = withContext(Dispatchers.Default) {
                    OverviewCalculator.calculate(
                        candles = currentCandles,
                        quote = tickToQuote(tick),
                        now = System.currentTimeMillis()
                    )
                }

                if (released) return@collect
                snapshot = next

                val now = System.currentTimeMillis()
                val full = now - lastFullRenderAt >= FULL_REFRESH_INTERVAL_MS
                applySnapshot(next, full = full)
            }
        }
    }

    private fun bindIdentity(instrument: Instrument) {
        nameText.text = instrument.name
        metaText.text = buildString {
            append(instrument.exchange)
            append(" • ")
            append(instrument.segment)
            append(" • ")
            append(instrument.instrumentType)
            if (instrument.category.isNotBlank()) {
                append(" • ")
                append(instrument.category)
            }
            if (instrument.subcategory.isNotBlank()) {
                append(" / ")
                append(instrument.subcategory)
            }
        }
    }

    private fun applySnapshot(
        snapshot: OverviewSnapshot,
        full: Boolean
    ) {
        if (released) return

        val stats = snapshot.stats
        priceText.text = formatPrice(stats.lastPrice)
        priceText.setTextColor(AppDesign.textPrimary)

        val change = stats.change
        val changePercent = stats.changePercent
        changeText.text = when {
            change != null && changePercent != null ->
                "${signed(change)}  (${signed(changePercent)}%)"
            change != null -> signed(change)
            else -> "Change not available"
        }
        changeText.setTextColor(
            when {
                change == null -> AppDesign.textMuted
                change > 0.0 -> AppDesign.positive
                change < 0.0 -> AppDesign.negative
                else -> AppDesign.textMuted
            }
        )

        stateText.text = snapshot.state.label
        stateText.background = AppDesign.roundedBackground(
            context,
            stateColor(snapshot.state),
            7
        )
        stateText.setTextColor(
            if (snapshot.state == OverviewDataState.NOT_AVAILABLE) {
                AppDesign.textPrimary
            } else {
                Color.BLACK
            }
        )

        updatedText.text = buildString {
            append(snapshot.stateMessage)
            if (snapshot.lastUpdatedMillis != null) {
                append("  •  Updated ")
                append(dateFormat.format(Date(snapshot.lastUpdatedMillis)))
            }
        }

        if (full) {
            lastFullRenderAt = System.currentTimeMillis()
            renderOverview(snapshot)
        }
    }

    private fun renderOverview(snapshot: OverviewSnapshot) {
        content.removeAllViews()
        val instrument = instrument

        if (instrument == null) {
            content.addView(
                AppDesign.text(
                    context,
                    "Instrument metadata is unavailable.",
                    12f,
                    AppDesign.negative
                )
            )
            return
        }

        if (snapshot.state == OverviewDataState.NOT_AVAILABLE) {
            content.addView(createNoticeCard(snapshot.stateMessage))
            content.addView(createAdditionalInfo(instrument))
            return
        }

        content.addView(createWeek52Card(snapshot))
        content.addView(createKeyStats(snapshot))
        content.addView(createPerformanceCard())
        content.addView(createReturnsCard(snapshot))
        content.addView(createIndicatorsCard(snapshot))
        content.addView(createTrendCard(snapshot))
        content.addView(createAdditionalInfo(instrument))
        content.addView(createDisclosure(snapshot))
    }

    private fun createNoticeCard(message: String): View {
        return AppDesign.text(
            context,
            message,
            11f,
            AppDesign.accent
        ).apply {
            background = AppDesign.cardBackground(context, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }
    }

    private fun createWeek52Card(snapshot: OverviewSnapshot): View {
        val stats = snapshot.stats
        val card = createCard()

        card.addView(sectionTitle("52-WEEK RANGE"))

        if (stats.weekHigh52 == null || stats.weekLow52 == null) {
            card.addView(
                AppDesign.text(
                    context,
                    insufficientText(snapshot),
                    11f,
                    AppDesign.textMuted
                ).apply { setPadding(0, dp(6), 0, dp(4)) }
            )
            return card
        }

        val high = stats.weekHigh52
        val low = stats.weekLow52
        val highRow = createValueRow(
            "52W High",
            formatPrice(high),
            AppDesign.positive,
            stats.weekHigh52Timestamp
        )
        val lowRow = createValueRow(
            "52W Low",
            formatPrice(low),
            AppDesign.negative,
            stats.weekLow52Timestamp
        )
        card.addView(highRow)
        card.addView(lowRow)

        card.addView(
            createValueRow(
                "From 52W High",
                formatPercent(stats.distanceFromHigh52Percent),
                percentColor(stats.distanceFromHigh52Percent)
            )
        )
        card.addView(
            createValueRow(
                "From 52W Low",
                formatPercent(stats.distanceFromLow52Percent),
                percentColor(stats.distanceFromLow52Percent)
            )
        )

        val coverage = stats.week52CoverageDays
        card.addView(
            AppDesign.text(
                context,
                if (coverage >= 300L) {
                    "Coverage: $coverage days of stored daily OHLC."
                } else {
                    "Partial coverage: $coverage days of stored daily OHLC " +
                        "(less than a full 52-week window)."
                },
                9f,
                if (coverage >= 300L) AppDesign.textMuted else AppDesign.accent
            ).apply { setPadding(0, dp(5), 0, 0) }
        )

        return card
    }

    private fun createKeyStats(snapshot: OverviewSnapshot): View {
        val card = createCard()
        card.addView(sectionTitle("PRICE STATS"))
        val stats = snapshot.stats

        card.addView(
            createMetricGrid(
                listOf(
                    "Open" to formatPrice(stats.open),
                    "High" to formatPrice(stats.high),
                    "Low" to formatPrice(stats.low),
                    "Prev Close" to formatPrice(stats.previousClose),
                    "Volume" to formatVolume(stats.volume),
                    "Daily Candles" to snapshot.dailyCandleCount.toString()
                )
            )
        )

        return card
    }

    private fun createPerformanceCard(): View {
        val card = createCard()
        card.addView(sectionTitle("HISTORICAL PERFORMANCE"))
        card.addView(
            AppDesign.text(
                context,
                "Close-price chart from stored daily OHLC candles.",
                10f,
                AppDesign.textMuted
            ).apply { setPadding(0, dp(2), 0, dp(6)) }
        )

        chartView = OverviewLineChartView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(190)
            )
        }
        card.addView(chartView)

        val ranges = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, 0)
        }
        rangeChips.clear()
        listOf("1M", "3M", "6M", "1Y", "MAX").forEach { label ->
            val chip = AppDesign.text(
                context,
                label,
                10f,
                AppDesign.textPrimary,
                bold = true
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(5), dp(7), dp(5))
                setOnClickListener {
                    selectedRange = label
                    refreshRangeChips()
                    refreshChart()
                }
            }
            rangeChips[label] = chip
            ranges.addView(
                chip,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { setMargins(0, 0, dp(3), 0) }
            )
        }
        card.addView(ranges)

        refreshRangeChips()
        refreshChart()
        return card
    }

    private fun refreshRangeChips() {
        rangeChips.forEach { (label, chip) ->
            val active = label == selectedRange
            chip.setTextColor(
                if (active) Color.BLACK else AppDesign.textPrimary
            )
            chip.background = AppDesign.roundedBackground(
                context,
                if (active) AppDesign.accent else AppDesign.surface,
                7
            )
        }
    }

    private fun refreshChart() {
        val snapshot = snapshot ?: return
        val history = snapshot.history
        if (history.isEmpty()) {
            chartView.setData(emptyList(), selectedRange)
            return
        }

        val lastTimestamp = history.last().timestamp
        val days = when (selectedRange) {
            "1M" -> 30L
            "3M" -> 90L
            "6M" -> 180L
            "1Y" -> 365L
            else -> Long.MAX_VALUE / 4L
        }
        val cutoff = if (days >= Long.MAX_VALUE / 8L) {
            Long.MIN_VALUE
        } else {
            lastTimestamp - days * DAY_MILLIS
        }
        val filtered = history.filter { it.timestamp >= cutoff }
        chartView.setData(
            filtered.ifEmpty { history.takeLast(1) },
            selectedRange
        )
    }

    private fun createReturnsCard(snapshot: OverviewSnapshot): View {
        val card = createCard()
        card.addView(sectionTitle("PERIOD RETURNS"))

        val values = snapshot.returns.map { item ->
            item.label to formatPercent(item.percent)
        }
        card.addView(createMetricGrid(values))

        card.addView(
            AppDesign.text(
                context,
                "Returns use the latest price against the closest stored " +
                    "daily close at or before each period start.",
                9f,
                AppDesign.textMuted
            ).apply { setPadding(0, dp(5), 0, 0) }
        )

        return card
    }

    private fun createIndicatorsCard(snapshot: OverviewSnapshot): View {
        val card = createCard()
        card.addView(sectionTitle("TECHNICAL INDICATORS"))

        snapshot.indicators.forEach { indicator ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }

            val label = AppDesign.text(
                context,
                indicator.label,
                11f,
                AppDesign.textPrimary
            )
            row.addView(
                label,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            row.addView(
                AppDesign.text(
                    context,
                    indicator.value,
                    11f,
                    AppDesign.textPrimary,
                    bold = true
                ).apply {
                    setPadding(dp(6), 0, dp(7), 0)
                }
            )

            row.addView(
                AppDesign.text(
                    context,
                    signalLabel(indicator.signal),
                    9f,
                    AppDesign.textPrimary,
                    bold = true
                ).apply {
                    gravity = Gravity.CENTER
                    background = AppDesign.roundedBackground(
                        context,
                        signalColor(indicator.signal),
                        6
                    )
                    setPadding(dp(6), dp(3), dp(6), dp(3))
                }
            )

            card.addView(row)
        }

        card.addView(
            AppDesign.text(
                context,
                "Indicators are calculated from stored daily candles using " +
                    "the existing IndicatorService. They are not trading signals.",
                9f,
                AppDesign.textMuted
            ).apply { setPadding(0, dp(6), 0, 0) }
        )

        return card
    }

    private fun createTrendCard(snapshot: OverviewSnapshot): View {
        val card = createCard()
        val trend = snapshot.trend
        card.addView(sectionTitle("TREND CONDITIONS"))

        card.addView(
            AppDesign.text(
                context,
                trend.label,
                18f,
                trendColor(trend.label),
                bold = true
            ).apply { setPadding(0, dp(2), 0, dp(2)) }
        )

        card.addView(
            AppDesign.text(
                context,
                "${trend.bullishCount} bullish • " +
                    "${trend.bearishCount} bearish • " +
                    "${trend.neutralCount} neutral " +
                    "(${trend.availableCount} available)",
                10f,
                AppDesign.textMuted
            ).apply { setPadding(0, 0, 0, dp(4)) }
        )

        trend.conditions.forEach { condition ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(
                AppDesign.text(
                    context,
                    signalLabel(condition.signal),
                    9f,
                    AppDesign.textPrimary,
                    bold = true
                ).apply {
                    gravity = Gravity.CENTER
                    background = AppDesign.roundedBackground(
                        context,
                        signalColor(condition.signal),
                        6
                    )
                    setPadding(dp(6), dp(3), dp(6), dp(3))
                }
            )
            row.addView(
                AppDesign.text(
                    context,
                    "  ${condition.label}  •  ${condition.detail}",
                    10f,
                    AppDesign.textPrimary
                )
            )
            card.addView(row)
        }

        return card
    }

    private fun createAdditionalInfo(instrument: Instrument): View {
        val card = createCard()
        card.addView(sectionTitle("INSTRUMENT INFORMATION"))

        card.addView(
            createInfoRow("Exchange", instrument.exchange)
        )
        card.addView(
            createInfoRow("Segment", instrument.segment)
        )
        card.addView(
            createInfoRow("Instrument type", instrument.instrumentType)
        )
        card.addView(
            createInfoRow(
                "Category",
                instrument.category.ifBlank { "Not available" }
            )
        )
        card.addView(
            createInfoRow(
                "Subcategory",
                instrument.subcategory.ifBlank { "Not available" }
            )
        )
        card.addView(
            createInfoRow("Lot size", instrument.lotSize.toString())
        )
        card.addView(
            createInfoRow(
                "Tick size",
                formatNumber(instrument.tickSize, 4)
            )
        )
        card.addView(
            createInfoRow(
                "ISIN",
                instrument.isin?.takeIf { it.isNotBlank() }
                    ?: "Not available"
            )
        )
        card.addView(
            createInfoRow(
                "Expiry",
                instrument.expiryMillis?.let {
                    dayFormat.format(Date(it))
                } ?: "Not applicable / not available"
            )
        )
        card.addView(
            createInfoRow(
                "Token",
                instrument.token?.takeIf { it.isNotBlank() }
                    ?: "Not available"
            )
        )
        card.addView(
            createInfoRow(
                "Source",
                instrument.source.ifBlank { "Not available" }
            )
        )
        card.addView(
            AppDesign.text(
                context,
                "Sector, industry, market cap, P/E, dividend yield and " +
                    "other fundamentals are not present in the local " +
                    "instrument master and are not fabricated.",
                9f,
                AppDesign.textMuted
            ).apply { setPadding(0, dp(7), 0, 0) }
        )

        return card
    }

    private fun createDisclosure(snapshot: OverviewSnapshot): View {
        return AppDesign.text(
            context,
            "Data: quote from the Kotak market-data pipeline; 52-week range, " +
                "returns and indicators from stored daily OHLC candles. " +
                "Historical daily candles do not cover every instrument " +
                "(for example, MCX depends on bhavcopy backfill). " +
                "This Overview is informational and is not a trading recommendation.",
            9f,
            AppDesign.textMuted
        ).apply {
            setPadding(dp(2), dp(4), dp(2), dp(10))
            if (snapshot.dailyCandleCount < 20) {
                setTextColor(AppDesign.accent)
            }
        }
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AppDesign.cardBackground(context, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }
    }

    private fun sectionTitle(value: String): TextView {
        return AppDesign.text(
            context,
            value,
            10f,
            AppDesign.accent,
            bold = true
        ).apply { setPadding(0, 0, 0, dp(3)) }
    }

    private fun createValueRow(
        label: String,
        value: String,
        color: Int,
        timestamp: Long? = null
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }

        val labelBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        labelBlock.addView(
            AppDesign.text(
                context,
                label,
                11f,
                AppDesign.textMuted
            )
        )
        if (timestamp != null) {
            labelBlock.addView(
                AppDesign.text(
                    context,
                    dayFormat.format(Date(timestamp)),
                    9f,
                    AppDesign.textMuted
                )
            )
        }
        row.addView(labelBlock)

        row.addView(
            AppDesign.text(
                context,
                value,
                13f,
                color,
                bold = true
            )
        )
        return row
    }

    private fun createInfoRow(label: String, value: String): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(
            AppDesign.text(
                context,
                label,
                10f,
                AppDesign.textMuted
            ),
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0.45f
            )
        )
        row.addView(
            AppDesign.text(
                context,
                value,
                10f,
                AppDesign.textPrimary
            ),
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0.55f
            )
        )
        return row
    }

    private fun createMetricGrid(values: List<Pair<String, String>>): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        values.chunked(2).forEach { rowItems ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            rowItems.forEach { (label, value) ->
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = AppDesign.roundedBackground(
                        context,
                        AppDesign.surfaceSolid,
                        8
                    )
                    setPadding(dp(9), dp(7), dp(9), dp(7))
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply { setMargins(0, 0, dp(4), dp(4)) }
                }
                cell.addView(
                    AppDesign.text(
                        context,
                        label,
                        9f,
                        AppDesign.textMuted
                    )
                )
                cell.addView(
                    AppDesign.text(
                        context,
                        value,
                        12f,
                        AppDesign.textPrimary,
                        bold = true
                    ).apply { setPadding(0, dp(2), 0, 0) }
                )
                row.addView(cell)
            }

            if (rowItems.size == 1) {
                row.addView(
                    View(context),
                    LinearLayout.LayoutParams(
                        0,
                        1,
                        1f
                    ).apply { setMargins(0, 0, dp(4), 0) }
                )
            }
            container.addView(row)
        }

        return container
    }

    private fun insufficientText(snapshot: OverviewSnapshot): String {
        return if (snapshot.dailyCandleCount == 0) {
            "No stored daily OHLC candles. Historical import or bhavcopy " +
                "backfill is required before a 52-week range can be calculated."
        } else {
            "Only ${snapshot.dailyCandleCount} daily candles are stored. " +
                "At least 20 are required before showing a 52-week range."
        }
    }

    private fun tickToQuote(tick: MarketTick): Quote? {
        if (tick.ltp <= 0.0 || !tick.ltp.isFinite()) return null
        return Quote(
            symbol = tick.key.symbol,
            exchange = tick.key.exchange,
            ltp = tick.ltp,
            open = tick.open,
            high = tick.high,
            low = tick.low,
            previousClose = tick.previousClose ?: tick.close,
            volume = tick.volume,
            change = tick.change,
            changePercent = tick.changePercent,
            timestamp = tick.receivedAt,
            isLive = true,
            exchangeTimestamp = tick.exchangeTime,
            receivedAt = tick.receivedAt
        )
    }

    private fun stateColor(state: OverviewDataState): Int {
        return when (state) {
            OverviewDataState.LIVE -> AppDesign.positive
            OverviewDataState.LAST_AVAILABLE -> AppDesign.accent
            OverviewDataState.DELAYED -> AppDesign.accent
            OverviewDataState.HISTORICAL -> AppDesign.textMuted
            OverviewDataState.NOT_AVAILABLE -> AppDesign.negative
            OverviewDataState.INSUFFICIENT_DATA -> AppDesign.accent
        }
    }

    private fun signalColor(signal: OverviewSignal): Int {
        return when (signal) {
            OverviewSignal.BULLISH -> AppDesign.positive
            OverviewSignal.BEARISH -> AppDesign.negative
            OverviewSignal.NEUTRAL -> AppDesign.textMuted
            OverviewSignal.NOT_AVAILABLE -> AppDesign.surfaceSolid
        }
    }

    private fun signalLabel(signal: OverviewSignal): String {
        return when (signal) {
            OverviewSignal.BULLISH -> "BULL"
            OverviewSignal.BEARISH -> "BEAR"
            OverviewSignal.NEUTRAL -> "NEUT"
            OverviewSignal.NOT_AVAILABLE -> "N/A"
        }
    }

    private fun trendColor(label: String): Int {
        return when (label) {
            "Bullish" -> AppDesign.positive
            "Bearish" -> AppDesign.negative
            else -> AppDesign.accent
        }
    }

    private fun percentColor(value: Double?): Int {
        return when {
            value == null -> AppDesign.textMuted
            value > 0.0 -> AppDesign.positive
            value < 0.0 -> AppDesign.negative
            else -> AppDesign.textMuted
        }
    }

    private fun formatPrice(value: Double?): String {
        if (value == null || !value.isFinite()) return "—"
        return "₹" + String.format(Locale.US, "%,.2f", value)
    }

    private fun formatNumber(value: Double, decimals: Int): String {
        if (!value.isFinite()) return "—"
        return String.format(Locale.US, "%.${decimals}f", value)
    }

    private fun formatPercent(value: Double?): String {
        if (value == null || !value.isFinite()) return "—"
        return String.format(
            Locale.US,
            "%s%.2f%%",
            if (value > 0.0) "+" else "",
            value
        )
    }

    private fun formatVolume(value: Long?): String {
        if (value == null || value < 0L) return "—"
        return when {
            value >= 10_000_000L ->
                String.format(Locale.US, "%.2f Cr", value / 10_000_000.0)
            value >= 100_000L ->
                String.format(Locale.US, "%.2f L", value / 100_000.0)
            value >= 1_000L ->
                String.format(Locale.US, "%.2f K", value / 1_000.0)
            else -> value.toString()
        }
    }

    private fun signed(value: Double): String {
        val sign = if (value > 0.0) "+" else if (value < 0.0) "-" else ""
        return String.format(
            Locale.US,
            "%s%.2f",
            sign,
            abs(value)
        )
    }

    private fun dp(value: Int): Int = AppDesign.dp(context, value)

    private data class LoadedOverview(
        val instrument: Instrument? = null,
        val candles: List<Candle> = emptyList(),
        val snapshot: OverviewSnapshot? = null,
        val error: String? = null
    )

    companion object {
        private const val DAY_MILLIS = 86_400_000L
        private const val FULL_REFRESH_INTERVAL_MS = 5_000L
    }
}
