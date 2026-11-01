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
        note: String = ""
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

            val price = entryPrice ?: marketRepository.getQuote(
                normalizedSymbol,
                normalizedExchange
            )?.ltp

            if (price == null || !price.isFinite() || price <= 0.0) {
                return TradeResult(
                    success = false,
                    message = "Valid entry price is unavailable."
                )
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
                note = note
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
        exchange: String = AppConfig.get().defaultExchange
    ): TradeResult {
        return try {
            val order = paperRepository.getOrder(orderId)
                ?: return TradeResult(
                    success = false,
                    message = "Paper order not found."
                )

            if (!order.isOpen) {
                return TradeResult(
                    success = false,
                    message = "Paper order is already closed."
                )
            }

            val price = exitPrice ?: marketRepository.getQuote(
                order.symbol,
                order.exchange
            )?.ltp

            if (price == null || !price.isFinite() || price <= 0.0) {
                return TradeResult(
                    success = false,
                    message = "Valid exit price is unavailable."
                )
            }

            val closed = paperRepository.closeOrder(orderId, price)
                ?: return TradeResult(
                    success = false,
                    message = "Could not close paper order."
                )

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

    fun markToMarket(
        symbols: List<String>? = null,
        exchange: String = AppConfig.get().defaultExchange
    ): Int {
        val positions = paperRepository.getPositions(true)
        if (positions.isEmpty()) return 0

        val targets = symbols?.takeIf { it.isNotEmpty() }
            ?: positions.map { it.symbol }.distinct()
        val quotes = marketRepository.getQuotes(targets, exchange)
        if (quotes.isEmpty()) return 0

        val prices = HashMap<String, Double>()
        quotes.forEach { quote ->
            prices[quote.symbol] = quote.ltp
        }
        return paperRepository.updatePositionPrices(prices)
    }

    fun snapshot(): PaperSnapshot? {
        val account = paperRepository.getAccount() ?: return null
        val positions = paperRepository.getPositions(true)
        val realized = paperRepository.getRealizedPnl()
        val unrealized = paperRepository.getUnrealizedPnl()
        return PaperSnapshot(
            account = account,
            openPositions = positions,
            realizedPnl = realized,
            unrealizedPnl = unrealized,
            totalEquity = account.balance + unrealized
        )
    }

    fun getOpenOrders(): List<PaperOrder> {
        return paperRepository.getOpenOrders()
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
