package com.sb.ui.chart

data class ChartCandle(
    val timestamp: Long = 0L,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long = 0L
)

enum class ChartStyle(val label: String) {
    CANDLE("CDL"),
    HEIKIN_ASHI("HA"),
    LINE("LINE"),
    BAR("BAR")
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
