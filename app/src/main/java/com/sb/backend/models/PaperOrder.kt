package com.sb.backend.models

enum class OrderSide {
    BUY,
    SELL
}

enum class OrderStatus {
    OPEN,
    CLOSED
}

data class PaperOrder(
    val id: Long = 0L,
    val symbol: String,
    val exchange: String = "NSE",
    val side: String,
    val quantity: Int,
    val entryPrice: Double,
    val exitPrice: Double? = null,
    val realizedPnl: Double? = null,
    val reservedMargin: Double = 0.0,
    val status: String = OrderStatus.OPEN.name,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val orderValue: Double
        get() = quantity.toDouble() * entryPrice

    val isOpen: Boolean
        get() = status == OrderStatus.OPEN.name
}
