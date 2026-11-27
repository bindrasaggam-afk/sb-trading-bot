package com.sb.backend.models

enum class OrderSide {
    BUY,
    SELL
}

enum class OrderStatus {
    PENDING,
    OPEN,
    CLOSED,
    CANCELLED,
    REJECTED
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
    val updatedAt: Long = System.currentTimeMillis(),
    val stopLoss: Double? = null,
    val target: Double? = null,
    val entrySource: String = "MARKET",
    val entryTimestamp: Long? = null,
    val signalId: Long? = null,
    val signalCategory: String = "",
    val signalTimeframe: String = "",
    val strategyId: String = "",
    val strategyVersion: String = "",
    val signalGeneratedAt: Long? = null,
    val signalExpiresAt: Long? = null,
    val entryLow: Double? = null,
    val entryHigh: Double? = null,
    val costBpsPerSide: Double = 0.0,
    val slippageBpsPerSide: Double = 0.0,
    val target1: Double? = null,
    val target2: Double? = null,
    val target3: Double? = null,
    val remainingQuantity: Int = quantity,
    val lastPrice: Double? = entryPrice,
    val entryCharges: Double = 0.0,
    val charges: Double = 0.0,
    val exitReason: String = "",
    val target1At: Long? = null,
    val target2At: Long? = null,
    val target3At: Long? = null,
    val stoppedAt: Long? = null
) {
    val orderValue: Double
        get() = quantity.toDouble() * entryPrice

    val isOpen: Boolean
        get() = status == OrderStatus.OPEN.name

    val isPending: Boolean
        get() = status == OrderStatus.PENDING.name

    val isActive: Boolean
        get() = isOpen || isPending

    val isTerminal: Boolean
        get() = status == OrderStatus.CLOSED.name ||
            status == OrderStatus.CANCELLED.name ||
            status == OrderStatus.REJECTED.name
}

enum class PaperExecutionKind {
    ENTRY,
    TARGET_1,
    TARGET_2,
    TARGET_3,
    STOP_LOSS,
    EXPIRY,
    EXIT
}

data class PaperExecution(
    val id: Long = 0L,
    val orderId: Long,
    val signalId: Long? = null,
    val kind: PaperExecutionKind,
    val quantity: Int,
    val price: Double,
    val grossPnl: Double = 0.0,
    val charges: Double = 0.0,
    val netPnl: Double = 0.0,
    val executedAt: Long,
    val eventKey: String,
    val note: String = ""
)

data class PaperTradeEvent(
    val id: Long = 0L,
    val orderId: Long? = null,
    val signalId: Long? = null,
    val type: String,
    val message: String,
    val createdAt: Long = System.currentTimeMillis()
)
