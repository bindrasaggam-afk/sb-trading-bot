package com.sb.backend.models

enum class SignalType {
    BUY,
    SELL,
    WAIT
}

enum class TradingStyle {
    INTRADAY,
    DELIVERY
}

enum class SignalStatus {
    ACTIVE,
    CONFIRMED,
    EXPIRED,
    CANCELLED
}

data class TradingSignal(
    val id: Long = 0L,
    val symbol: String,
    val exchange: String,
    val tradingStyle: TradingStyle,
    val signal: SignalType,
    val entryPrice: Double? = null,
    val stopLoss: Double? = null,
    val target1: Double? = null,
    val target2: Double? = null,
    val target3: Double? = null,
    val confidence: Double = 0.0,
    val timeframe: String = "5m",
    val status: SignalStatus = SignalStatus.ACTIVE,
    val reason: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null,
    val confirmationCount: Int = 0,
    val confirmationRequired: Int = 2,
    val isStable: Boolean = false
) {
    init {
        require(symbol.isNotBlank())
        require(confidence in 0.0..100.0)
        require(confirmationCount >= 0)
        require(confirmationRequired > 0)
    }
}
