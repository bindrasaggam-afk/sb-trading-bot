package com.sb.backend.services

import com.sb.backend.core.AppConfig
import com.sb.backend.models.OrderSide
import com.sb.backend.models.Quote
import java.util.Locale

data class RiskDecision(
    val allowed: Boolean,
    val requiredFunds: Double = 0.0,
    val reservedMargin: Double = 0.0,
    val reason: String? = null
)

class RiskManager {

    fun calculateReservedMargin(
        side: String,
        quantity: Int,
        price: Double
    ): Double {
        if (quantity <= 0 || !price.isFinite() || price <= 0.0) return 0.0
        if (!side.trim().equals(OrderSide.SELL.name, ignoreCase = true)) {
            return 0.0
        }
        return quantity.toDouble() * price *
            AppConfig.get().shortMarginRate
    }

    fun requiredFunds(
        side: String,
        quantity: Int,
        price: Double
    ): Double {
        require(quantity > 0) {
            "Quantity must be greater than zero."
        }
        require(price.isFinite() && price > 0.0) {
            "Price must be valid and positive."
        }

        val normalizedSide = side.trim().toUpperCase(Locale.ROOT)

        return when (normalizedSide) {
            OrderSide.BUY.name -> quantity.toDouble() * price
            OrderSide.SELL.name -> calculateReservedMargin(
                normalizedSide,
                quantity,
                price
            )
            else -> throw IllegalArgumentException("Side must be BUY or SELL.")
        }
    }

    fun validateOrder(
        side: String,
        quantity: Int,
        price: Double,
        availableBalance: Double,
        openPositions: Int
    ): RiskDecision {
        if (quantity <= 0) {
            return RiskDecision(
                allowed = false,
                reason = "Quantity must be greater than zero."
            )
        }

        if (!price.isFinite() || price <= 0.0) {
            return RiskDecision(
                allowed = false,
                reason = "Price must be valid and positive."
            )
        }

        val normalizedSide = side.trim().toUpperCase(Locale.ROOT)
        if (normalizedSide != OrderSide.BUY.name &&
            normalizedSide != OrderSide.SELL.name
        ) {
            return RiskDecision(
                allowed = false,
                reason = "Side must be BUY or SELL."
            )
        }

        if (!availableBalance.isFinite() || availableBalance <= 0.0) {
            return RiskDecision(
                allowed = false,
                reason = "Available balance is insufficient."
            )
        }

        if (openPositions < 0) {
            return RiskDecision(
                allowed = false,
                reason = "Open positions cannot be negative."
            )
        }

        val config = AppConfig.get()
        if (openPositions >= config.maxOpenPositions) {
            return RiskDecision(
                allowed = false,
                reason = "Maximum open positions reached."
            )
        }

        val reserved = calculateReservedMargin(
            normalizedSide,
            quantity,
            price
        )
        val required = requiredFunds(normalizedSide, quantity, price)

        if (required > availableBalance + 0.000001) {
            return RiskDecision(
                allowed = false,
                requiredFunds = required,
                reservedMargin = reserved,
                reason = "Insufficient available funds. Required: " +
                    String.format(Locale.US, "%.2f", required)
            )
        }

        return RiskDecision(
            allowed = true,
            requiredFunds = required,
            reservedMargin = reserved
        )
    }

    fun isQuoteFresh(
        quote: Quote,
        maxAgeMs: Long = 60_000L
    ): Boolean {
        if (maxAgeMs < 0L) return false
        if (!quote.ltp.isFinite() || quote.ltp <= 0.0) return false

        val age = System.currentTimeMillis() - quote.timestamp
        return age >= 0L && age <= maxAgeMs
    }

    fun canTrade(
        marketOpen: Boolean,
        liveDataEnabled: Boolean = AppConfig.get().liveMarketDataEnabled
    ): RiskDecision {
        if (!marketOpen) {
            return RiskDecision(
                allowed = false,
                reason = "Market is closed."
            )
        }
        if (!liveDataEnabled) {
            return RiskDecision(
                allowed = false,
                reason = "Live market data is disabled."
            )
        }
        return RiskDecision(allowed = true)
    }
}