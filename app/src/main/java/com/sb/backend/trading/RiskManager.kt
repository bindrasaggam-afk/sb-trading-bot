package com.sb.backend.trading

import com.sb.backend.core.AppConfig
import com.sb.backend.models.OrderSide
import com.sb.backend.models.Position
import kotlin.math.abs
import kotlin.math.floor

data class RiskCheckResult(
    val allowed: Boolean,
    val message: String,
    val requiredMargin: Double = 0.0,
    val availableCapital: Double = 0.0,
    val estimatedRisk: Double = 0.0
)

data class PositionSizingResult(
    val allowed: Boolean,
    val quantity: Int,
    val requiredMargin: Double,
    val estimatedRisk: Double,
    val message: String
)

object RiskManager {

    private fun isValidNumber(value: Double): Boolean {
        return !value.isNaN() && !value.isInfinite()
    }

    private fun normalizeSide(side: String): String {
        return side.trim().toUpperCase()
    }

    private fun marginRate(side: String): Double {
        return if (normalizeSide(side) == OrderSide.SELL.name) {
            AppConfig.get().shortMarginRate
        } else {
            1.0
        }
    }

    fun calculateRequiredMargin(
        side: String,
        quantity: Int,
        price: Double
    ): Double {
        if (quantity <= 0 || price <= 0.0 || !isValidNumber(price)) {
            return 0.0
        }

        val rate = marginRate(side)

        if (!isValidNumber(rate) || rate <= 0.0) {
            return 0.0
        }

        return quantity.toDouble() * price * rate
    }

    fun calculatePositionSize(
        capital: Double,
        riskPercent: Double,
        entryPrice: Double,
        stopLoss: Double,
        side: String
    ): PositionSizingResult {

        if (!isValidNumber(capital) || capital <= 0.0) {
            return PositionSizingResult(
                false, 0, 0.0, 0.0, "Invalid capital"
            )
        }

        if (!isValidNumber(riskPercent) ||
            riskPercent <= 0.0 || riskPercent > 100.0
        ) {
            return PositionSizingResult(
                false, 0, 0.0, 0.0,
                "Risk percent must be between 0 and 100"
            )
        }

        if (!isValidNumber(entryPrice) ||
            !isValidNumber(stopLoss) ||
            entryPrice <= 0.0 || stopLoss <= 0.0
        ) {
            return PositionSizingResult(
                false, 0, 0.0, 0.0,
                "Invalid entry price or stop loss"
            )
        }

        val normalizedSide = normalizeSide(side)

        if (normalizedSide != OrderSide.BUY.name &&
            normalizedSide != OrderSide.SELL.name
        ) {
            return PositionSizingResult(
                false, 0, 0.0, 0.0, "Invalid order side"
            )
        }

        val riskPerUnit = abs(entryPrice - stopLoss)

        if (riskPerUnit <= 0.0) {
            return PositionSizingResult(
                false, 0, 0.0, 0.0,
                "Entry and stop loss cannot be equal"
            )
        }

        if (normalizedSide == OrderSide.BUY.name &&
            stopLoss >= entryPrice
        ) {
            return PositionSizingResult(
                false, 0, 0.0, 0.0,
                "BUY stop loss must be below entry price"
            )
        }

        if (normalizedSide == OrderSide.SELL.name &&
            stopLoss <= entryPrice
        ) {
            return PositionSizingResult(
                false, 0, 0.0, 0.0,
                "SELL stop loss must be above entry price"
            )
        }

        val allowedRisk = capital * riskPercent / 100.0
        val riskQuantity = floor(allowedRisk / riskPerUnit).toInt()

        if (riskQuantity <= 0) {
            return PositionSizingResult(
                false, 0, 0.0, 0.0,
                "Capital is insufficient for this risk"
            )
        }

        val rate = marginRate(normalizedSide)

        if (!isValidNumber(rate) || rate <= 0.0) {
            return PositionSizingResult(
                false, 0, 0.0, 0.0, "Invalid margin rate"
            )
        }

        val marginQuantity = floor(
            capital / (entryPrice * rate)
        ).toInt()

        val finalQuantity = minOf(riskQuantity, marginQuantity)

        if (finalQuantity <= 0) {
            return PositionSizingResult(
                false, 0, 0.0, 0.0,
                "Insufficient available capital"
            )
        }

        val requiredMargin = calculateRequiredMargin(
            normalizedSide, finalQuantity, entryPrice
        )

        val estimatedRisk = finalQuantity * riskPerUnit

        return PositionSizingResult(
            allowed = true,
            quantity = finalQuantity,
            requiredMargin = requiredMargin,
            estimatedRisk = estimatedRisk,
            message = "Position size calculated successfully"
        )
    }

    fun assessOrder(
        capital: Double,
        symbol: String,
        side: String,
        quantity: Int,
        entryPrice: Double,
        stopLoss: Double,
        openPositions: List<Position>
    ): RiskCheckResult {

        if (symbol.trim().isEmpty()) {
            return RiskCheckResult(false, "Symbol cannot be empty")
        }

        if (!isValidNumber(capital) || capital <= 0.0) {
            return RiskCheckResult(false, "Invalid capital")
        }

        if (quantity <= 0) {
            return RiskCheckResult(false, "Quantity must be greater than zero")
        }

        if (!isValidNumber(entryPrice) || entryPrice <= 0.0) {
            return RiskCheckResult(false, "Invalid entry price")
        }

        if (!isValidNumber(stopLoss) || stopLoss <= 0.0) {
            return RiskCheckResult(false, "Invalid stop loss")
        }

        val normalizedSide = normalizeSide(side)

        if (normalizedSide != OrderSide.BUY.name &&
            normalizedSide != OrderSide.SELL.name
        ) {
            return RiskCheckResult(false, "Invalid order side")
        }

        if (normalizedSide == OrderSide.BUY.name &&
            stopLoss >= entryPrice
        ) {
            return RiskCheckResult(
                false, "BUY stop loss must be below entry price"
            )
        }

        if (normalizedSide == OrderSide.SELL.name &&
            stopLoss <= entryPrice
        ) {
            return RiskCheckResult(
                false, "SELL stop loss must be above entry price"
            )
        }

        val activePositions = openPositions.filter { it.isOpen }
        val maxPositions = AppConfig.get().maxOpenPositions

        if (maxPositions > 0 && activePositions.size >= maxPositions) {
            return RiskCheckResult(
                false, "Maximum open positions limit reached"
            )
        }

        val requiredMargin = calculateRequiredMargin(
            normalizedSide, quantity, entryPrice
        )

        if (requiredMargin <= 0.0) {
            return RiskCheckResult(
                false, "Unable to calculate required margin"
            )
        }

        val usedMargin = calculateTotalUsedMargin(activePositions)
        val availableCapital = (capital - usedMargin).coerceAtLeast(0.0)

        if (requiredMargin > availableCapital) {
            return RiskCheckResult(
                allowed = false,
                message = "Insufficient available capital",
                requiredMargin = requiredMargin,
                availableCapital = availableCapital
            )
        }

        val estimatedRisk = abs(entryPrice - stopLoss) * quantity

        return RiskCheckResult(
            allowed = true,
            message = "Order passed risk checks",
            requiredMargin = requiredMargin,
            availableCapital = availableCapital,
            estimatedRisk = estimatedRisk
        )
    }

    fun calculatePositionMargin(position: Position): Double {
        if (!position.isOpen) return 0.0

        if (position.reservedMargin > 0.0 &&
            isValidNumber(position.reservedMargin)
        ) {
            return position.reservedMargin
        }

        return calculateRequiredMargin(
            position.side,
            position.quantity,
            position.entryPrice
        )
    }

    fun calculateTotalUsedMargin(
        positions: List<Position>
    ): Double {
        return positions
            .filter { it.isOpen }
            .sumByDouble { calculatePositionMargin(it) }
    }

    fun calculateAvailableCapital(
        totalCapital: Double,
        positions: List<Position>
    ): Double {
        if (!isValidNumber(totalCapital) || totalCapital <= 0.0) {
            return 0.0
        }

        val usedMargin = calculateTotalUsedMargin(positions)

        return (totalCapital - usedMargin).coerceAtLeast(0.0)
    }
}
