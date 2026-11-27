package com.sb.overview

import com.sb.backend.models.Candle
import com.sb.backend.models.Quote
import com.sb.backend.services.IndicatorService
import kotlin.math.max
import kotlin.math.min

enum class OverviewDataState(val label: String) {
    LIVE("LIVE"),
    LAST_AVAILABLE("LAST AVAILABLE"),
    DELAYED("DELAYED"),
    HISTORICAL("HISTORICAL"),
    NOT_AVAILABLE("NOT AVAILABLE"),
    INSUFFICIENT_DATA("INSUFFICIENT DATA")
}

enum class OverviewSignal(val label: String) {
    BULLISH("Bullish"),
    BEARISH("Bearish"),
    NEUTRAL("Neutral"),
    NOT_AVAILABLE("Not available")
}

data class OverviewHistoryPoint(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

data class OverviewPriceStats(
    val lastPrice: Double? = null,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val previousClose: Double? = null,
    val change: Double? = null,
    val changePercent: Double? = null,
    val volume: Long? = null,
    val weekHigh52: Double? = null,
    val weekLow52: Double? = null,
    val weekHigh52Timestamp: Long? = null,
    val weekLow52Timestamp: Long? = null,
    val distanceFromHigh52Percent: Double? = null,
    val distanceFromLow52Percent: Double? = null,
    val week52CoverageDays: Long = 0L
)

data class OverviewReturn(
    val label: String,
    val percent: Double?
)

data class OverviewIndicator(
    val key: String,
    val label: String,
    val value: String,
    val signal: OverviewSignal
)

data class OverviewTrendCondition(
    val label: String,
    val signal: OverviewSignal,
    val detail: String
)

data class OverviewTrend(
    val label: String,
    val bullishCount: Int,
    val bearishCount: Int,
    val neutralCount: Int,
    val availableCount: Int,
    val conditions: List<OverviewTrendCondition>
)

data class OverviewSnapshot(
    val state: OverviewDataState,
    val stateMessage: String,
    val lastUpdatedMillis: Long? = null,
    val quoteTimestampMillis: Long? = null,
    val lastCandleTimestampMillis: Long? = null,
    val stats: OverviewPriceStats = OverviewPriceStats(),
    val returns: List<OverviewReturn> = emptyList(),
    val history: List<OverviewHistoryPoint> = emptyList(),
    val indicators: List<OverviewIndicator> = emptyList(),
    val trend: OverviewTrend = OverviewTrend(
        label = "Insufficient data",
        bullishCount = 0,
        bearishCount = 0,
        neutralCount = 0,
        availableCount = 0,
        conditions = emptyList()
    ),
    val dailyCandleCount: Int = 0
)

/**
 * Pure calculation layer for the instrument Overview section.
 *
 * The 52-week range and all technical indicators are derived from stored
 * daily OHLC candles. The instrument master is never used to invent prices.
 */
object OverviewCalculator {

    private const val DAY_MILLIS = 86_400_000L
    private const val YEAR_MILLIS = 365L * DAY_MILLIS
    private const val LIVE_MAX_AGE_MILLIS = 5L * 60L * 1000L
    private const val DELAYED_AFTER_MILLIS = 15L * 60L * 1000L
    private const val MIN_RANGE_CANDLES = 20
    private const val PERIOD_TOLERANCE_MILLIS = 7L * DAY_MILLIS

    fun calculate(
        candles: List<Candle>,
        quote: Quote?,
        now: Long = System.currentTimeMillis()
    ): OverviewSnapshot {
        val daily = candles
            .filter {
                it.open.isFinite() &&
                    it.high.isFinite() &&
                    it.low.isFinite() &&
                    it.close.isFinite() &&
                    it.open > 0.0 &&
                    it.high > 0.0 &&
                    it.low > 0.0 &&
                    it.close > 0.0 &&
                    it.high >= it.low
            }
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }

        val history = daily.map {
            OverviewHistoryPoint(
                timestamp = it.timestamp,
                open = it.open,
                high = it.high,
                low = it.low,
                close = it.close,
                volume = max(0L, it.volume)
            )
        }

        val lastCandle = history.lastOrNull()
        val lastCandleTimestamp = lastCandle?.timestamp
        val lastPrice = quote?.ltp?.takeIf { it.isFinite() && it > 0.0 }
            ?: lastCandle?.close

        val fallbackPreviousClose = if (history.size >= 2) {
            history[history.size - 2].close
        } else {
            null
        }

        val previousClose = quote?.previousClose?.takeIf {
            it.isFinite() && it > 0.0
        } ?: fallbackPreviousClose

        val change = quote?.change?.takeIf { it.isFinite() }
            ?: if (lastPrice != null && previousClose != null) {
                lastPrice - previousClose
            } else {
                null
            }

        val changePercent = quote?.changePercent?.takeIf { it.isFinite() }
            ?: if (change != null && previousClose != null && previousClose > 0.0) {
                change / previousClose * 100.0
            } else {
                null
            }

        val week52 = calculateWeek52(history)

        val stats = OverviewPriceStats(
            lastPrice = lastPrice,
            open = quote?.open?.takeIf { it.isFinite() }
                ?: lastCandle?.open,
            high = quote?.high?.takeIf { it.isFinite() }
                ?: lastCandle?.high,
            low = quote?.low?.takeIf { it.isFinite() }
                ?: lastCandle?.low,
            previousClose = previousClose,
            change = change,
            changePercent = changePercent,
            volume = quote?.volume?.takeIf { it >= 0L }
                ?: lastCandle?.volume,
            weekHigh52 = week52.high,
            weekLow52 = week52.low,
            weekHigh52Timestamp = week52.highTimestamp,
            weekLow52Timestamp = week52.lowTimestamp,
            distanceFromHigh52Percent = distancePercent(
                lastPrice,
                week52.high
            ),
            distanceFromLow52Percent = distancePercent(
                lastPrice,
                week52.low
            ),
            week52CoverageDays = week52.coverageDays
        )

        val returns = listOf(
            "1W" to periodReturn(history, 7L, lastPrice),
            "1M" to periodReturn(history, 30L, lastPrice),
            "3M" to periodReturn(history, 90L, lastPrice),
            "6M" to periodReturn(history, 180L, lastPrice),
            "1Y" to periodReturn(history, 365L, lastPrice)
        ).map { (label, percent) -> OverviewReturn(label, percent) }

        val indicators = calculateIndicators(history, lastPrice)
        val trend = calculateTrend(indicators)

        val quoteTimestamp = quote?.receivedAt?.takeIf { it > 0L }
        val lastUpdated = listOfNotNull(quoteTimestamp, lastCandleTimestamp)
            .maxOrNull()

        val (state, message) = resolveState(
            quote = quote,
            hasHistory = history.isNotEmpty(),
            dailyCandleCount = history.size,
            quoteTimestamp = quoteTimestamp,
            now = now
        )

        return OverviewSnapshot(
            state = state,
            stateMessage = message,
            lastUpdatedMillis = lastUpdated,
            quoteTimestampMillis = quoteTimestamp,
            lastCandleTimestampMillis = lastCandleTimestamp,
            stats = stats,
            returns = returns,
            history = history,
            indicators = indicators,
            trend = trend,
            dailyCandleCount = history.size
        )
    }

    private data class Week52Range(
        val high: Double? = null,
        val low: Double? = null,
        val highTimestamp: Long? = null,
        val lowTimestamp: Long? = null,
        val coverageDays: Long = 0L
    )

    private fun calculateWeek52(
        history: List<OverviewHistoryPoint>
    ): Week52Range {
        val last = history.lastOrNull() ?: return Week52Range()
        val windowStart = last.timestamp - YEAR_MILLIS
        val window = history.filter { it.timestamp >= windowStart }

        if (window.size < MIN_RANGE_CANDLES) {
            return Week52Range(
                coverageDays = coverageDays(window.firstOrNull(), last)
            )
        }

        val highest = window.maxByOrNull { it.high }
        val lowest = window.minByOrNull { it.low }
        val coverage = coverageDays(window.first(), last)

        // lastPrice is intentionally not used to widen the range:
        // the range must come from executed OHLC candles only.
        return Week52Range(
            high = highest?.high,
            low = lowest?.low,
            highTimestamp = highest?.timestamp,
            lowTimestamp = lowest?.timestamp,
            coverageDays = coverage
        )
    }

    private fun coverageDays(
        first: OverviewHistoryPoint?,
        last: OverviewHistoryPoint?
    ): Long {
        if (first == null || last == null) return 0L
        return ((last.timestamp - first.timestamp) / DAY_MILLIS)
            .coerceAtLeast(0L)
    }

    private fun distancePercent(
        price: Double?,
        reference: Double?
    ): Double? {
        if (price == null || reference == null ||
            !price.isFinite() || !reference.isFinite() ||
            reference <= 0.0
        ) return null
        return (price - reference) / reference * 100.0
    }

    private fun periodReturn(
        history: List<OverviewHistoryPoint>,
        days: Long,
        endPrice: Double?
    ): Double? {
        if (history.size < 2 || endPrice == null || endPrice <= 0.0) {
            return null
        }

        val last = history.last()
        val target = last.timestamp - days * DAY_MILLIS
        val first = history.first()

        if (first.timestamp > target + PERIOD_TOLERANCE_MILLIS) {
            return null
        }

        val reference = history.lastOrNull { it.timestamp <= target }
            ?: first

        if (reference.close <= 0.0) return null

        return (endPrice - reference.close) / reference.close * 100.0
    }

    private fun calculateIndicators(
        history: List<OverviewHistoryPoint>,
        lastPrice: Double?
    ): List<OverviewIndicator> {
        if (history.isEmpty()) {
            return indicatorKeys().map {
                OverviewIndicator(
                    key = it.first,
                    label = it.second,
                    value = "—",
                    signal = OverviewSignal.NOT_AVAILABLE
                )
            }
        }

        val closes = history.map { it.close }
        val candles = history.map {
            Candle(
                symbol = "OVERVIEW",
                timeframe = "1D",
                timestamp = it.timestamp,
                open = it.open,
                high = it.high,
                low = it.low,
                close = it.close,
                volume = it.volume
            )
        }

        val price = lastPrice?.takeIf { it > 0.0 } ?: closes.last()
        val ema20 = lastValid(IndicatorService.ema(closes, 20))
        val sma50 = lastValid(IndicatorService.sma(closes, 50))
        val rsi14 = lastValid(IndicatorService.rsi(closes, 14))
        val atr14 = lastValid(IndicatorService.atr(candles, 14))
        val macd = IndicatorService.macd(closes)
        val macdLine = lastValid(macd.macdLine)
        val macdSignal = lastValid(macd.signalLine)
        val macdHistogram = lastValid(macd.histogram)
        val supertrend = IndicatorService.supertrend(candles)
        val supertrendLine = lastValid(supertrend.line)
        val supertrendDirection = supertrend.direction
            .lastOrNull { it != 0 }
        val vwap = lastValid(IndicatorService.vwap(candles))
        val supportResistance = IndicatorService.supportResistance(
            candles,
            lookback = min(50, candles.size)
        )

        return listOf(
            OverviewIndicator(
                key = "EMA20",
                label = "EMA 20",
                value = formatPrice(ema20),
                signal = directionalSignal(price, ema20)
            ),
            OverviewIndicator(
                key = "SMA50",
                label = "SMA 50",
                value = formatPrice(sma50),
                signal = directionalSignal(price, sma50)
            ),
            OverviewIndicator(
                key = "RSI14",
                label = "RSI 14",
                value = formatNumber(rsi14, 2),
                signal = when {
                    rsi14 == null -> OverviewSignal.NOT_AVAILABLE
                    rsi14 >= 55.0 -> OverviewSignal.BULLISH
                    rsi14 <= 45.0 -> OverviewSignal.BEARISH
                    else -> OverviewSignal.NEUTRAL
                }
            ),
            OverviewIndicator(
                key = "MACD",
                label = "MACD (12,26,9)",
                value = if (macdLine == null) "—" else {
                    "${formatNumber(macdLine, 2)} / ${formatNumber(macdSignal, 2)}"
                },
                signal = when {
                    macdHistogram == null -> OverviewSignal.NOT_AVAILABLE
                    macdHistogram > 0.0 -> OverviewSignal.BULLISH
                    macdHistogram < 0.0 -> OverviewSignal.BEARISH
                    else -> OverviewSignal.NEUTRAL
                }
            ),
            OverviewIndicator(
                key = "SUPERTREND",
                label = "Supertrend (10,3)",
                value = formatPrice(supertrendLine),
                signal = when (supertrendDirection) {
                    1 -> OverviewSignal.BULLISH
                    -1 -> OverviewSignal.BEARISH
                    else -> OverviewSignal.NOT_AVAILABLE
                }
            ),
            OverviewIndicator(
                key = "ATR14",
                label = "ATR 14",
                value = formatNumber(atr14, 2),
                signal = if (atr14 == null) {
                    OverviewSignal.NOT_AVAILABLE
                } else {
                    OverviewSignal.NEUTRAL
                }
            ),
            OverviewIndicator(
                key = "VWAP",
                label = "VWAP (daily candles)",
                value = formatPrice(vwap),
                signal = directionalSignal(price, vwap)
            ),
            OverviewIndicator(
                key = "SR",
                label = "Support / Resistance",
                value = if (supportResistance == null) "—" else {
                    "${formatPrice(supportResistance.first)} / " +
                        formatPrice(supportResistance.second)
                },
                signal = OverviewSignal.NEUTRAL
            )
        )
    }

    private fun indicatorKeys(): List<Pair<String, String>> = listOf(
        "EMA20" to "EMA 20",
        "SMA50" to "SMA 50",
        "RSI14" to "RSI 14",
        "MACD" to "MACD (12,26,9)",
        "SUPERTREND" to "Supertrend (10,3)",
        "ATR14" to "ATR 14",
        "VWAP" to "VWAP (daily candles)",
        "SR" to "Support / Resistance"
    )

    private fun calculateTrend(
        indicators: List<OverviewIndicator>
    ): OverviewTrend {
        val directionalKeys = setOf(
            "EMA20",
            "SMA50",
            "RSI14",
            "MACD",
            "SUPERTREND",
            "VWAP"
        )

        val conditions = indicators
            .filter { it.key in directionalKeys }
            .map {
                OverviewTrendCondition(
                    label = it.label,
                    signal = it.signal,
                    detail = it.value
                )
            }

        val available = conditions.filter {
            it.signal != OverviewSignal.NOT_AVAILABLE
        }
        val bullish = available.count { it.signal == OverviewSignal.BULLISH }
        val bearish = available.count { it.signal == OverviewSignal.BEARISH }
        val neutral = available.count { it.signal == OverviewSignal.NEUTRAL }

        val label = when {
            available.size < 3 -> "Insufficient data"
            bullish > bearish && bullish * 100 / available.size >= 60 ->
                "Bullish"
            bearish > bullish && bearish * 100 / available.size >= 60 ->
                "Bearish"
            else -> "Mixed / Neutral"
        }

        return OverviewTrend(
            label = label,
            bullishCount = bullish,
            bearishCount = bearish,
            neutralCount = neutral,
            availableCount = available.size,
            conditions = conditions
        )
    }

    private fun directionalSignal(
        price: Double,
        reference: Double?
    ): OverviewSignal {
        if (reference == null || !reference.isFinite()) {
            return OverviewSignal.NOT_AVAILABLE
        }
        return when {
            price > reference -> OverviewSignal.BULLISH
            price < reference -> OverviewSignal.BEARISH
            else -> OverviewSignal.NEUTRAL
        }
    }

    private fun lastValid(values: List<Double?>): Double? {
        for (index in values.indices.reversed()) {
            val value = values[index]
            if (value != null && value.isFinite()) return value
        }
        return null
    }

    private fun resolveState(
        quote: Quote?,
        hasHistory: Boolean,
        dailyCandleCount: Int,
        quoteTimestamp: Long?,
        now: Long
    ): Pair<OverviewDataState, String> {
        if (quote == null && !hasHistory) {
            return OverviewDataState.NOT_AVAILABLE to
                "No stored quote or daily candles are available for this instrument."
        }

        if (quote == null) {
            return if (dailyCandleCount < MIN_RANGE_CANDLES) {
                OverviewDataState.INSUFFICIENT_DATA to
                    "No stored quote, and only $dailyCandleCount daily candle(s) are available. " +
                    "At least $MIN_RANGE_CANDLES are required for range and indicator calculations."
            } else {
                OverviewDataState.HISTORICAL to
                    "No stored quote. Overview is calculated from historical daily candles."
            }
        }

        val age = if (quoteTimestamp == null) Long.MAX_VALUE else now - quoteTimestamp
        val state = when {
            quote.isLive && age <= LIVE_MAX_AGE_MILLIS -> OverviewDataState.LIVE
            age > DELAYED_AFTER_MILLIS -> OverviewDataState.DELAYED
            else -> OverviewDataState.LAST_AVAILABLE
        }

        val message = when {
            !hasHistory || dailyCandleCount < MIN_RANGE_CANDLES ->
                "Quote available. Historical daily candles are insufficient for a complete 52-week range and indicators."
            state == OverviewDataState.DELAYED ->
                "Quote is older than 15 minutes. Values may not reflect the current market."
            state == OverviewDataState.LAST_AVAILABLE ->
                "Showing the latest stored quote. Waiting for a fresh live tick."
            else -> "Live quote connected. 52-week range and indicators use stored daily OHLC candles."
        }

        return state to message
    }

    private fun formatPrice(value: Double?): String {
        if (value == null || !value.isFinite()) return "—"
        return String.format(java.util.Locale.US, "%.2f", value)
    }

    private fun formatNumber(value: Double?, decimals: Int): String {
        if (value == null || !value.isFinite()) return "—"
        return String.format(java.util.Locale.US, "%.${decimals}f", value)
    }
}
