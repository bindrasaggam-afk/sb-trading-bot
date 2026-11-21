package com.sb.ui.chart.persistence

object ChartStateScope {
    fun key(symbol: String, timeframe: String, exchange: String = ""): String =
        listOf(exchange.trim(), symbol.trim(), timeframe.trim())
            .joinToString("|") { it.replace("|", "_") }
}
