package com.sb.backend.models

data class Quote(
    val symbol: String,
    val exchange: String,
    val ltp: Double,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val previousClose: Double? = null,
    val volume: Long = 0L,
    val change: Double? = null,
    val changePercent: Double? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isLive: Boolean = false,
    val exchangeTimestamp: Long? = null,
    val receivedAt: Long = timestamp
) {
    init {
        require(symbol.isNotBlank())
        require(exchange.isNotBlank())
        require(ltp.isFinite() && ltp >= 0.0)
        require(volume >= 0L)
        require(timestamp > 0L)
        require(receivedAt > 0L)
    }
}

data class Candle(
    val symbol: String,
    val timeframe: String,
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long = 0L,
    val isComplete: Boolean = true
) {
    init {
        require(symbol.isNotBlank())
        require(timeframe.isNotBlank())
        require(open >= 0.0 && high >= 0.0)
        require(low >= 0.0 && close >= 0.0)
        require(high >= low) { "High cannot be below Low" }
        require(volume >= 0L)
    }
}
