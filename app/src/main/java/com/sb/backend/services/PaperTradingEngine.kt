package com.sb.backend.services

import android.util.Log
import com.sb.backend.core.AppConfig
import com.sb.backend.models.OrderSide
import com.sb.backend.models.OrderStatus
import com.sb.backend.models.PaperExecution
import com.sb.backend.models.PaperExecutionKind
import com.sb.backend.models.PaperOrder
import com.sb.backend.models.PaperTradeEvent
import com.sb.backend.models.Position
import com.sb.backend.repositories.MarketRepository
import com.sb.backend.repositories.PaperAccount
import com.sb.backend.repositories.PaperRepository
import com.sb.backend.trading.PaperEntryDecisionType
import com.sb.backend.trading.PaperSignalCandidate
import com.sb.backend.trading.PaperSignalPolicy
import java.util.Locale
import kotlin.math.min

data class PaperSnapshot(
    val account: PaperAccount,
    val openPositions: List<Position>,
    val realizedPnl: Double,
    val unrealizedPnl: Double,
    val totalEquity: Double
) {
    val availableBalance: Double
        get() = account.availableBalance
}

data class TradeResult(
    val success: Boolean,
    val message: String,
    val order: PaperOrder? = null,
    val pnl: Double? = null,
    val snapshot: PaperSnapshot? = null
)

private data class TargetExit(
    val kind: PaperExecutionKind,
    val level: Double?,
    val executedAt: Long?,
    val quantity: Int
)

class PaperTradingEngine(
    private val paperRepository: PaperRepository,
    private val marketRepository: MarketRepository,
    private val riskManager: RiskManager = RiskManager()
) {

    fun setStartingBalance(startingBalance: Double): TradeResult {
        return try {
            paperRepository.setStartingBalance(startingBalance)
            TradeResult(
                success = true,
                message = "Starting balance saved.",
                snapshot = snapshot()
            )
        } catch (e: Exception) {
            TradeResult(
                success = false,
                message = e.message ?: "Could not save starting balance."
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun placeOrder(
        symbol: String,
        side: String,
        quantity: Int,
        entryPrice: Double? = null,
        exchange: String = AppConfig.get().defaultExchange,
        note: String = "",
        stopLoss: Double? = null,
        target: Double? = null,
        entryTimestamp: Long? = null
    ): TradeResult {
        return TradeResult(
            success = false,
            message = "Manual paper orders are disabled. " +
                "Trades are created only from genuine Research Signals."
        )
    }

    fun closeOrder(
        orderId: Long,
        exitPrice: Double? = null,
        @Suppress("UNUSED_PARAMETER")
        exchange: String = AppConfig.get().defaultExchange,
        exitTimestamp: Long? = null
    ): TradeResult {
        return try {
            val order = paperRepository.getOrder(orderId)
                ?: return TradeResult(
                    success = false,
                    message = "Paper order not found."
                )

            if (!order.isOpen) {
                return TradeResult(
                    success = true,
                    message = "Paper order is already closed.",
                    order = order,
                    pnl = order.realizedPnl,
                    snapshot = snapshot()
                )
            }

            val quote = if (exitPrice == null) {
                marketRepository.getQuote(order.symbol, order.exchange)
            } else {
                null
            }
            val price = exitPrice ?: quote?.ltp

            if (price == null || !price.isFinite() || price <= 0.0) {
                return TradeResult(
                    success = false,
                    message = "Valid exit price is unavailable."
                )
            }

            val quoteTimestamp = exitTimestamp
                ?: quote?.receivedAt
                ?: if (exitPrice != null) System.currentTimeMillis() else null
            if (quoteTimestamp == null ||
                !riskManager.isQuoteFresh(quoteTimestamp)
            ) {
                return TradeResult(
                    success = false,
                    message = "Exit quote is stale. Wait for a fresh tick."
                )
            }

            val closed = paperRepository.closeOrder(orderId, price)
            if (closed == null) {
                val latest = paperRepository.getOrder(orderId)
                if (latest != null && !latest.isOpen) {
                    return TradeResult(
                        success = true,
                        message = "Paper order is already closed.",
                        order = latest,
                        pnl = latest.realizedPnl,
                        snapshot = snapshot()
                    )
                }
                return TradeResult(
                    success = false,
                    message = "Could not close paper order."
                )
            }

            TradeResult(
                success = true,
                message = "Paper order closed.",
                order = closed,
                pnl = closed.realizedPnl,
                snapshot = snapshot()
            )
        } catch (e: Exception) {
            TradeResult(
                success = false,
                message = e.message ?: "Could not close paper order."
            )
        }
    }

    fun markToMarket(symbols: List<String>? = null): Int {
        val positions = paperRepository.getPositions(true)
        if (positions.isEmpty()) return 0

        val symbolFilter = symbols
            ?.map { it.trim().uppercase(Locale.ROOT) }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }

        var updated = 0
        positions
            .filter { symbolFilter == null || it.symbol.uppercase(Locale.ROOT) in symbolFilter }
            .groupBy { it.exchange }
            .forEach { (exchange, exchangePositions) ->
                val quotes = marketRepository.getQuotes(
                    exchangePositions.map { it.symbol }.distinct(),
                    exchange
                )
                val prices = HashMap<String, Double>()
                quotes.forEach { quote ->
                    if (riskManager.isQuoteFresh(quote)) {
                        prices[quote.symbol] = quote.ltp
                    }
                }
                if (prices.isNotEmpty()) {
                    updated += paperRepository.updatePositionPrices(
                        prices,
                        exchange
                    )
                }
            }
        return updated
    }

    fun monitorOpenTrades(now: Long = System.currentTimeMillis()): Int {
        markToMarket()
        var actions = 0

        paperRepository.getPendingOrders().forEach { order ->
            if (order.signalId == null) return@forEach
            try {
                if (monitorPendingEntry(order, now)) actions++
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "Pending paper signal ${order.signalId} failed.",
                    error
                )
            }
        }

        paperRepository.getOpenOrders().forEach { order ->
            if (order.signalId == null) return@forEach
            try {
                if (monitorOpenSignal(order, now)) actions++
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "Open paper signal ${order.signalId} failed.",
                    error
                )
            }
        }
        return actions
    }

    private fun monitorPendingEntry(
        order: PaperOrder,
        now: Long
    ): Boolean {
        val signalId = order.signalId ?: return false
        val candidate = signalCandidate(order)
        if (candidate == null) {
            paperRepository.cancelSignalOrder(
                orderId = order.id,
                reason = "Invalid signal levels on forwarded order",
                createdAt = now
            )
            return true
        }

        val quote = marketRepository.getQuote(order.symbol, order.exchange)
        val fresh = quote != null && riskManager.isQuoteFresh(quote)
        val decision = PaperSignalPolicy.entryDecision(
            candidate = candidate,
            quotePrice = quote?.ltp ?: Double.NaN,
            quoteFresh = fresh,
            now = now
        )
        return when (decision.type) {
            PaperEntryDecisionType.WAIT -> false
            PaperEntryDecisionType.CANCEL -> {
                paperRepository.cancelSignalOrder(
                    orderId = order.id,
                    reason = decision.reason,
                    createdAt = now
                )
                true
            }
            PaperEntryDecisionType.FILL -> {
                val price = decision.price ?: return false
                val currentQuote = quote ?: return false
                try {
                    paperRepository.fillSignalOrder(
                        orderId = order.id,
                        executionPrice = price,
                        executedAt = currentQuote.receivedAt
                    )
                    true
                } catch (error: Exception) {
                    Log.e(
                        TAG,
                        "Paper entry rejected for signal $signalId.",
                        error
                    )
                    paperRepository.cancelSignalOrder(
                        orderId = order.id,
                        reason = "Entry rejected: " +
                            (error.message ?: "unknown error"),
                        createdAt = now
                    )
                    true
                }
            }
        }
    }

    private fun monitorOpenSignal(
        order: PaperOrder,
        now: Long
    ): Boolean {
        if (signalCandidate(order) == null) return false
        if (order.remainingQuantity <= 0) return false

        val quote = marketRepository.getQuote(order.symbol, order.exchange)
            ?: return false
        if (!riskManager.isQuoteFresh(quote)) return false
        val ltp = quote.ltp
        if (!ltp.isFinite() || ltp <= 0.0) return false

        val expiresAt = order.signalExpiresAt ?: return false
        if (now >= expiresAt) {
            return exitRemaining(
                order = order,
                kind = PaperExecutionKind.EXPIRY,
                price = PaperSignalPolicy.marketExecutionPrice(
                    side = order.side,
                    quotePrice = ltp,
                    slippageBps = order.slippageBpsPerSide
                ),
                executedAt = quote.receivedAt,
                note = "Signal expired"
            )
        }

        val stopLoss = order.stopLoss ?: return false
        if (PaperSignalPolicy.isStopHit(order.side, ltp, stopLoss)) {
            return exitRemaining(
                order = order,
                kind = PaperExecutionKind.STOP_LOSS,
                price = PaperSignalPolicy.stopExecutionPrice(
                    side = order.side,
                    quotePrice = ltp,
                    stopLoss = stopLoss,
                    slippageBps = order.slippageBpsPerSide
                ),
                executedAt = quote.receivedAt,
                note = "Stop loss reached"
            )
        }

        val quantities = PaperSignalPolicy.partialQuantities(order.quantity)
        val targets = listOf(
            TargetExit(
                kind = PaperExecutionKind.TARGET_1,
                level = order.target1,
                executedAt = order.target1At,
                quantity = quantities.first
            ),
            TargetExit(
                kind = PaperExecutionKind.TARGET_2,
                level = order.target2,
                executedAt = order.target2At,
                quantity = quantities.second
            ),
            TargetExit(
                kind = PaperExecutionKind.TARGET_3,
                level = order.target3,
                executedAt = order.target3At,
                quantity = quantities.third
            )
        )

        var current = order
        var exited = false
        targets.forEach { target ->
            if (current.remainingQuantity <= 0) return@forEach
            val level = target.level ?: return@forEach
            if (target.executedAt != null) return@forEach
            if (!PaperSignalPolicy.isTargetHit(order.side, ltp, level)) {
                return@forEach
            }

            val quantity = if (target.kind == PaperExecutionKind.TARGET_3) {
                current.remainingQuantity
            } else {
                min(target.quantity, current.remainingQuantity)
            }
            if (quantity <= 0) return@forEach

            val executionPrice = if (
                order.side == OrderSide.BUY.name
            ) {
                maxOf(level, ltp)
            } else {
                minOf(level, ltp)
            }
            val updated = paperRepository.exitSignalOrder(
                orderId = current.id,
                kind = target.kind,
                quantity = quantity,
                executionPrice = executionPrice,
                executedAt = quote.receivedAt,
                note = "${target.kind.name} reached"
            )
            if (updated != null) {
                current = updated
                exited = true
            }
        }
        return exited
    }

    private fun exitRemaining(
        order: PaperOrder,
        kind: PaperExecutionKind,
        price: Double,
        executedAt: Long,
        note: String
    ): Boolean {
        if (!price.isFinite() || price <= 0.0) return false
        val updated = paperRepository.exitSignalOrder(
            orderId = order.id,
            kind = kind,
            quantity = order.remainingQuantity,
            executionPrice = price,
            executedAt = executedAt,
            note = note
        ) ?: return false
        return updated.id > 0L
    }

    private fun signalCandidate(order: PaperOrder): PaperSignalCandidate? {
        val signalId = order.signalId ?: return null
        val entryLow = order.entryLow ?: return null
        val entryHigh = order.entryHigh ?: return null
        val stopLoss = order.stopLoss ?: return null
        val target1 = order.target1 ?: return null
        val target2 = order.target2 ?: return null
        val target3 = order.target3 ?: return null
        val expiresAt = order.signalExpiresAt ?: return null
        if (!entryLow.isFinite() || !entryHigh.isFinite() ||
            !stopLoss.isFinite() || !target1.isFinite() ||
            !target2.isFinite() || !target3.isFinite()
        ) {
            return null
        }
        return PaperSignalCandidate(
            signalId = signalId,
            symbol = order.symbol,
            exchange = order.exchange,
            side = order.side,
            category = order.signalCategory,
            timeframe = order.signalTimeframe,
            strategyId = order.strategyId,
            strategyVersion = order.strategyVersion,
            referencePrice = order.entryPrice,
            entryLow = entryLow,
            entryHigh = entryHigh,
            stopLoss = stopLoss,
            target1 = target1,
            target2 = target2,
            target3 = target3,
            generatedAt = order.signalGeneratedAt ?: order.createdAt,
            expiresAt = expiresAt,
            costBpsPerSide = order.costBpsPerSide,
            slippageBpsPerSide = order.slippageBpsPerSide
        )
    }

    fun snapshot(): PaperSnapshot? {
        val data = paperRepository.getSnapshotData() ?: return null
        return PaperSnapshot(
            account = data.account,
            openPositions = data.positions,
            realizedPnl = data.realizedPnl,
            unrealizedPnl = data.unrealizedPnl,
            totalEquity = data.account.startingBalance +
                data.realizedPnl +
                data.unrealizedPnl
        )
    }

    fun getOpenOrders(): List<PaperOrder> {
        return paperRepository.getOpenOrders()
    }

    fun getPendingOrders(): List<PaperOrder> {
        return paperRepository.getPendingOrders()
    }

    fun getActiveOrders(): List<PaperOrder> {
        return paperRepository.getActiveOrders()
    }

    fun getExecutions(
        orderId: Long? = null,
        limit: Int = 200
    ): List<PaperExecution> {
        return paperRepository.getExecutions(orderId, limit)
    }

    fun getEvents(limit: Int = 200): List<PaperTradeEvent> {
        return paperRepository.getEvents(limit)
    }

    fun getSignalOrder(signalId: Long): PaperOrder? {
        return paperRepository.getSignalOrder(signalId)
    }

    fun getOrder(orderId: Long): PaperOrder? {
        return paperRepository.getOrder(orderId)
    }

    fun getOpenOrder(symbol: String, exchange: String): PaperOrder? {
        val normalizedSymbol = symbol.trim()
        val normalizedExchange = exchange.trim()
        return paperRepository.getOpenOrders().firstOrNull { order ->
            order.symbol.equals(normalizedSymbol, ignoreCase = true) &&
                order.exchange.equals(normalizedExchange, ignoreCase = true)
        }
    }

    fun getOrders(status: String? = null): List<PaperOrder> {
        return paperRepository.getOrders(status)
    }

    fun getPositions(openOnly: Boolean = true): List<Position> {
        return paperRepository.getPositions(openOnly)
    }

    fun reset(startingBalance: Double): TradeResult {
        return try {
            paperRepository.reset(startingBalance)
            TradeResult(
                success = true,
                message = "Paper account reset.",
                snapshot = snapshot()
            )
        } catch (e: Exception) {
            TradeResult(
                success = false,
                message = e.message ?: "Could not reset paper account."
            )
        }
    }

    fun formatAmount(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }

    fun validateExitPrice(price: Double): Boolean {
        return price.isFinite() && price > 0.0
    }

    fun normalizeSide(side: String): String {
        val normalized = side.trim().toUpperCase(Locale.ROOT)
        require(
            normalized == OrderSide.BUY.name ||
                normalized == OrderSide.SELL.name
        ) {
            "Side must be BUY or SELL."
        }
        return normalized
    }

    private companion object {
        private const val TAG = "PaperTradingEngine"
    }
}
