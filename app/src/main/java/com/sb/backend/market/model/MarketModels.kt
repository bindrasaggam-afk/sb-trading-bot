package com.sb.backend.market.model

data class DepthLevel(
    val price: Double = 0.0,
    val quantity: Long = 0L,
    val orders: Int = 0
)

data class InstrumentKey(
    val exchange: String,
    val symbol: String
) {
    fun key(): String = "${exchange.trim().toUpperCase()}|${symbol.trim().toUpperCase()}"
}

data class MarketTick(
    val key: InstrumentKey,
    val ltp: Double = 0.0,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val close: Double? = null,
    val previousClose: Double? = null,
    val volume: Long = 0L,
    val change: Double? = null,
    val changePercent: Double? = null,
    val bidPrice: Double? = null,
    val bidQty: Long? = null,
    val askPrice: Double? = null,
    val askQty: Long? = null,
    val bids: List<DepthLevel> = emptyList(),
    val asks: List<DepthLevel> = emptyList(),
    val totalBuyQty: Long = 0L,
    val totalSellQty: Long = 0L,
    val oi: Long = 0L,
    val lastTradeQty: Long = 0L,
    val lastTradeTime: Long? = null,
    val exchangeTime: Long? = null,
    val receivedAt: Long = System.currentTimeMillis(),
    val isIndex: Boolean = false
)

data class MarketUiState(
    val tick: MarketTick? = null,
    val isSubscribed: Boolean = false,
    val isConnected: Boolean = false,
    val isAuthenticated: Boolean = false,
    val status: String = "IDLE"
)
