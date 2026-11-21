package com.sb.ui.chart

data class ChartCandle(
    val timestamp: Long = 0L,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long = 0L
)

object ChartCandleSeries {

    fun canonicalize(input: List<ChartCandle>): List<ChartCandle> {
        if (input.isEmpty()) return emptyList()
        val byTimestamp = LinkedHashMap<Long, ChartCandle>()
        input.forEach { candle ->
            if (isValid(candle)) {
                byTimestamp[candle.timestamp] = candle
            }
        }
        return byTimestamp.values.sortedBy { it.timestamp }
    }

    fun isValid(candle: ChartCandle): Boolean {
        if (candle.timestamp <= 0L || candle.volume < 0L) return false
        if (!candle.open.isFinite() || !candle.high.isFinite() ||
            !candle.low.isFinite() || !candle.close.isFinite()
        ) return false
        if (candle.open <= 0.0 || candle.high <= 0.0 ||
            candle.low <= 0.0 || candle.close <= 0.0
        ) return false
        return candle.high >= maxOf(candle.open, candle.close, candle.low) &&
            candle.low <= minOf(candle.open, candle.close, candle.high)
    }
}

enum class ChartStyle(val label: String) {
    CANDLE("CDL"),
    HEIKIN_ASHI("HA"),
    LINE("LINE"),
    BAR("BAR")
}

enum class PriceScaleMode {
    ABSOLUTE,
    RELATIVE
}

enum class ChartTool(val label: String, val shortLabel: String) {
    CROSSHAIR("Crosshair", "+"),
    TRENDLINE("Trendline", "/"),
    HORIZONTAL("Horizontal line", "-"),
    FIBONACCI("Fibonacci retracement", "F"),
    TEXT("Text annotation", "T"),
    RULER("Measurement ruler", "R")
}

enum class ChartIndicator(val label: String) {
    EMA_20("EMA 20"),
    SMA_50("SMA 50"),
    VWAP("VWAP"),
    BOLLINGER("Bollinger Bands"),
    SUPERTREND("Supertrend"),
    RSI_14("RSI 14"),
    MACD("MACD")
}

data class ChartPoint(
    val index: Int,
    val price: Double
)

data class ChartDrawing(
    val tool: ChartTool,
    val points: MutableList<ChartPoint>,
    var text: String = ""
)
