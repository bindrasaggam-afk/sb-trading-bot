package com.sb.backend.services

import com.sb.backend.core.AppConfig
import com.sb.backend.models.OrderSide
import com.sb.backend.models.PaperOrder
import com.sb.backend.models.Position
import com.sb.backend.repositories.MarketRepository
import com.sb.backend.repositories.PaperAccount
import com.sb.backend.repositories.PaperRepository
import java.util.Locale

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
        return try {
            val account = paperRepository.getAccount()
                ?: return TradeResult(
                    success = false,
                    message = "Set starting balance first."
                )

            val normalizedSymbol = symbol.trim().toUpperCase(Locale.ROOT)
            val normalizedExchange = exchange.trim().toUpperCase(Locale.ROOT)
            val normalizedSide = normalizeSide(side)

            require(normalizedSymbol.isNotBlank()) {
                "Symbol cannot be empty."
            }
            require(normalizedExchange.isNotBlank()) {
                "Exchange cannot be empty."
            }

            val manualEntry = entryPrice != null
            val quote = if (!manualEntry) {
                marketRepository.getQuote(
                    normalizedSymbol,
                    normalizedExchange
                )
            } else {
                null
            }
            val price = entryPrice ?: quote?.ltp

            if (price == null || !price.isFinite() || price <= 0.0) {
                return TradeResult(
                    success = false,
                    message = "Valid entry price is unavailable."
                )
            }

            if (!manualEntry && !AppConfig.get().liveMarketDataEnabled) {
                return TradeResult(
                    success = false,
                    message = "Live market data is disabled."
                )
            }

            val quoteReceivedAt = entryTimestamp ?: quote?.receivedAt
                ?: if (manualEntry) System.currentTimeMillis() else null
            if (quoteReceivedAt == null ||
                !riskManager.isQuoteFresh(quoteReceivedAt)
            ) {
                return TradeResult(
                    success = false,
                    message = "Quote is stale. Wait for a fresh tick."
                )
            }

            if (stopLoss != null || target != null) {
                if (stopLoss == null || target == null) {
                    return TradeResult(
                        success = false,
                        message = "Stop loss and target must be provided together."
                    )
                }
                if (!stopLoss.isFinite() || stopLoss <= 0.0 ||
                    !target.isFinite() || target <= 0.0
                ) {
                    return TradeResult(
                        success = false,
                        message = "Stop loss and target must be valid and positive."
                    )
                }
                val validLevels = if (normalizedSide == OrderSide.BUY.name) {
                    stopLoss < price && target > price
                } else {
                    stopLoss > price && target < price
                }
                if (!validLevels) {
                    return TradeResult(
                        success = false,
                        message = "Invalid stop loss or target level."
                    )
                }
            }

            val decision = riskManager.validateOrder(
                side = normalizedSide,
                quantity = quantity,
                price = price,
                availableBalance = account.availableBalance,
                openPositions = paperRepository.getOpenPositionCount()
            )

            if (!decision.allowed) {
                return TradeResult(
                    success = false,
                    message = decision.reason ?: "Order rejected."
                )
            }

            val order = paperRepository.saveOrder(
                symbol = normalizedSymbol,
                exchange = normalizedExchange,
                side = normalizedSide,
                quantity = quantity,
                entryPrice = price,
                note = note,
                stopLoss = stopLoss,
                target = target,
                entrySource = if (manualEntry) "MANUAL" else "MARKET",
                entryTimestamp = quoteReceivedAt
            )

            TradeResult(
                success = true,
                message = "Paper order created.",
                order = order,
                snapshot = snapshot()
            )
        } catch (e: Exception) {
            TradeResult(
                success = false,
                message = e.message ?: "Could not create paper order."
            )
        }
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

    fun monitorOpenTrades(): Int {
        markToMarket()
        var closed = 0
        paperRepository.getOpenOrders().forEach { order ->
            val stopLoss = order.stopLoss ?: return@forEach
            val target = order.target ?: return@forEach
            if (!stopLoss.isFinite() || stopLoss <= 0.0 ||
                !target.isFinite() || target <= 0.0
            ) {
                return@forEach
            }

            val quote = marketRepository.getQuote(
                order.symbol,
                order.exchange
            ) ?: return@forEach
            if (!riskManager.isQuoteFresh(quote)) return@forEach

            val ltp = quote.ltp
            if (!ltp.isFinite() || ltp <= 0.0) return@forEach

            val isBuy = order.side == OrderSide.BUY.name
            val stopHit = if (isBuy) ltp <= stopLoss else ltp >= stopLoss
            val targetHit = if (isBuy) ltp >= target else ltp <= target
            if (!stopHit && !targetHit) return@forEach

            val result = closeOrder(
                orderId = order.id,
                exitPrice = ltp,
                exchange = order.exchange,
                exitTimestamp = quote.receivedAt
            )
            if (result.success) closed++
        }
        return closed
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
}
