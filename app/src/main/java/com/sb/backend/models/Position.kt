package com.sb.backend.models

data class Position(
    val id: Long = 0L,
    val orderId: Long = 0L,
    val symbol: String,
    val exchange: String = "NSE",
    val side: String,
    val quantity: Int,
    val entryPrice: Double,
    val lastPrice: Double = entryPrice,
    val reservedMargin: Double = 0.0,
    val status: String = OrderStatus.OPEN.name,
    val openedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val marketValue: Double
        get() = quantity.toDouble() * lastPrice

    val unrealizedPnl: Double
        get() {
            val direction = if (side == OrderSide.BUY.name) 1.0 else -1.0
            return (lastPrice - entryPrice) * quantity * direction
        }

    val isOpen: Boolean
        get() = status == OrderStatus.OPEN.name
}
