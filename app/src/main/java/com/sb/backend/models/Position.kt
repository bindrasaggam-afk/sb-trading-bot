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
    val updatedAt: Long = System.currentTimeMillis(),
    val estimatedCharges: Double = 0.0
) {
    val marketValue: Double
        get() = quantity.toDouble() * lastPrice

    val unrealizedPnl: Double
        get() {
            val direction = if (side == OrderSide.BUY.name) 1.0 else -1.0
            val gross = (lastPrice - entryPrice) * quantity * direction
            return gross - estimatedCharges.coerceAtLeast(0.0)
        }

    val isOpen: Boolean
        get() = status == OrderStatus.OPEN.name
}
