package com.sb.backend.trading

import com.sb.backend.models.OrderSide
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class PaperSignalCandidate(
    val signalId: Long,
    val symbol: String,
    val exchange: String,
    val side: String,
    val category: String,
    val timeframe: String,
    val strategyId: String,
    val strategyVersion: String,
    val referencePrice: Double,
    val entryLow: Double,
    val entryHigh: Double,
    val stopLoss: Double,
    val target1: Double,
    val target2: Double,
    val target3: Double,
    val generatedAt: Long,
    val expiresAt: Long,
    val costBpsPerSide: Double,
    val slippageBpsPerSide: Double
) {
    val key: String
        get() = "SIGNAL:$signalId"
}

data class PaperSizingRules(
    val riskPerTradePercent: Double,
    val allocationPercent: Double,
    val shortMarginRate: Double,
    val maxQuantity: Int,
    val costBpsPerSide: Double = 0.0
)

data class PaperCapitalRequirement(
    val orderValue: Double,
    val cashDebit: Double,
    val reservedMargin: Double,
    val entryCharge: Double
) {
    val totalReservation: Double
        get() = cashDebit + reservedMargin + entryCharge
}

enum class PaperEntryDecisionType {
    WAIT,
    FILL,
    CANCEL
}

data class PaperEntryDecision(
    val type: PaperEntryDecisionType,
    val price: Double? = null,
    val quantity: Int = 0,
    val reason: String = ""
)

object PaperSignalPolicy {

    fun initialQuantity(
        candidate: PaperSignalCandidate,
        availableBalance: Double,
        rules: PaperSizingRules,
        expectedEntryPrice: Double = candidate.referencePrice
    ): Int {
        if (!availableBalance.isFinite() || availableBalance <= 0.0) {
            return 0
        }
        val entry = expectedEntryPrice
        if (!entry.isFinite() || entry <= 0.0) return 0

        val riskPerUnit = abs(entry - candidate.stopLoss)
        val riskBudget = availableBalance *
            rules.riskPerTradePercent.coerceAtLeast(0.0) / 100.0
        val riskQuantity = if (riskPerUnit > 0.0) {
            floor(riskBudget / riskPerUnit).toLong()
        } else {
            Long.MAX_VALUE
        }
        val allocationQuantity = floor(
            availableBalance *
                rules.allocationPercent.coerceAtLeast(0.0) / 100.0 / entry
        ).toLong()
        val marginRate = rules.shortMarginRate.coerceIn(0.0, 1.0)
        val costRate = if (rules.costBpsPerSide.isFinite()) {
            rules.costBpsPerSide.coerceAtLeast(0.0) / 10_000.0
        } else {
            0.0
        }
        val unitCapital = if (candidate.side == OrderSide.SELL.name) {
            entry * (marginRate + costRate)
        } else {
            entry * (1.0 + costRate)
        }
        val affordableQuantity = if (unitCapital > 0.0) {
            floor(availableBalance / unitCapital).toLong()
        } else {
            Long.MAX_VALUE
        }
        val maxQuantity = if (rules.maxQuantity > 0) {
            rules.maxQuantity.toLong()
        } else {
            Long.MAX_VALUE
        }
        return minOf(
            riskQuantity,
            allocationQuantity,
            affordableQuantity,
            maxQuantity
        ).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    fun expectedEntryPrice(
        candidate: PaperSignalCandidate,
        applySlippage: Boolean = false
    ): Double {
        if (!candidate.referencePrice.isFinite() ||
            candidate.referencePrice <= 0.0
        ) {
            return 0.0
        }
        val low = min(candidate.entryLow, candidate.entryHigh)
        val high = max(candidate.entryLow, candidate.entryHigh)
        if (!low.isFinite() || !high.isFinite() || low <= 0.0) {
            return 0.0
        }
        val reference = candidate.referencePrice.coerceIn(low, high)
        if (!applySlippage) return reference
        val bps = if (candidate.slippageBpsPerSide.isFinite()) {
            candidate.slippageBpsPerSide.coerceAtLeast(0.0)
        } else {
            0.0
        }
        val slipped = if (candidate.side == OrderSide.BUY.name) {
            reference * (1.0 + bps / 10_000.0)
        } else {
            reference * (1.0 - bps / 10_000.0)
        }
        return slipped.coerceIn(low, high)
    }

    fun capitalRequirement(
        side: String,
        price: Double,
        quantity: Int,
        costBpsPerSide: Double,
        shortMarginRate: Double
    ): PaperCapitalRequirement {
        val safePrice = if (price.isFinite() && price > 0.0) price else 0.0
        val safeQuantity = quantity.coerceAtLeast(0)
        val orderValue = safePrice * safeQuantity
        val isSell = side == OrderSide.SELL.name
        val marginRate = shortMarginRate.coerceIn(0.0, 1.0)
        return PaperCapitalRequirement(
            orderValue = orderValue,
            cashDebit = if (isSell) 0.0 else orderValue,
            reservedMargin = if (isSell) {
                orderValue * marginRate
            } else {
                0.0
            },
            entryCharge = entryCharge(
                price = safePrice,
                quantity = safeQuantity,
                costBps = costBpsPerSide
            )
        )
    }

    fun entryDecision(
        candidate: PaperSignalCandidate,
        quotePrice: Double,
        quoteFresh: Boolean,
        now: Long
    ): PaperEntryDecision {
        if (now >= candidate.expiresAt) {
            return PaperEntryDecision(
                PaperEntryDecisionType.CANCEL,
                reason = "Signal expired before entry"
            )
        }
        if (!quotePrice.isFinite() || quotePrice <= 0.0) {
            return PaperEntryDecision(
                PaperEntryDecisionType.WAIT,
                reason = "Valid market price unavailable"
            )
        }
        if (!quoteFresh) {
            return PaperEntryDecision(
                PaperEntryDecisionType.WAIT,
                reason = "Market quote is stale"
            )
        }

        val isBuy = candidate.side == OrderSide.BUY.name
        val stopTouched = if (isBuy) {
            quotePrice <= candidate.stopLoss
        } else {
            quotePrice >= candidate.stopLoss
        }
        val targetTouched = if (isBuy) {
            quotePrice >= candidate.target1
        } else {
            quotePrice <= candidate.target1
        }
        if (stopTouched || targetTouched) {
            return PaperEntryDecision(
                PaperEntryDecisionType.CANCEL,
                reason = if (stopTouched) {
                    "Invalidated before entry: stop reached"
                } else {
                    "Invalidated before entry: target reached"
                }
            )
        }

        val low = min(candidate.entryLow, candidate.entryHigh)
        val high = max(candidate.entryLow, candidate.entryHigh)
        if (quotePrice < low || quotePrice > high) {
            return PaperEntryDecision(
                PaperEntryDecisionType.WAIT,
                reason = "Waiting for price to enter the signal range"
            )
        }

        val fillPrice = if (isBuy) {
            quotePrice * (
                1.0 + candidate.slippageBpsPerSide / 10_000.0
                )
        } else {
            quotePrice * (
                1.0 - candidate.slippageBpsPerSide / 10_000.0
                )
        }
        if (fillPrice < 0.0) {
            return PaperEntryDecision(
                PaperEntryDecisionType.WAIT,
                reason = "Simulated slippage produced an invalid price"
            )
        }
        if (isBuy && fillPrice > high) {
            return PaperEntryDecision(
                PaperEntryDecisionType.WAIT,
                reason = "Simulated entry slipped outside the signal range"
            )
        }
        if (!isBuy && fillPrice < low) {
            return PaperEntryDecision(
                PaperEntryDecisionType.WAIT,
                reason = "Simulated entry slipped outside the signal range"
            )
        }
        return PaperEntryDecision(
            PaperEntryDecisionType.FILL,
            price = fillPrice
        )
    }

    fun partialQuantities(total: Int): Triple<Int, Int, Int> {
        if (total <= 0) return Triple(0, 0, 0)
        if (total == 1) return Triple(1, 0, 0)
        if (total == 2) return Triple(1, 0, 1)

        val target1 = max(1, total * 40 / 100)
        val target2 = max(1, total * 30 / 100)
        val cappedTarget1 = min(target1, total - 1)
        val cappedTarget2 = min(target2, total - cappedTarget1 - 1)
        val target3 = total - cappedTarget1 - cappedTarget2
        return Triple(cappedTarget1, cappedTarget2, target3)
    }

    fun grossPnl(
        side: String,
        entryPrice: Double,
        exitPrice: Double,
        quantity: Int
    ): Double {
        val direction = if (side == OrderSide.BUY.name) 1.0 else -1.0
        return (exitPrice - entryPrice) * quantity * direction
    }

    fun chargesFor(value: Double, bps: Double): Double {
        if (!value.isFinite() || value <= 0.0) return 0.0
        if (!bps.isFinite() || bps <= 0.0) return 0.0
        return value * bps / 10_000.0
    }

    fun entryCharge(
        price: Double,
        quantity: Int,
        costBps: Double
    ): Double = chargesFor(price * quantity, costBps)

    fun exitCharge(
        price: Double,
        quantity: Int,
        costBps: Double
    ): Double = chargesFor(price * quantity, costBps)

    fun allocatedEntryCharge(
        totalEntryCharge: Double,
        originalQuantity: Int,
        exitQuantity: Int
    ): Double {
        if (totalEntryCharge <= 0.0 ||
            originalQuantity <= 0 ||
            exitQuantity <= 0
        ) {
            return 0.0
        }
        return totalEntryCharge *
            exitQuantity.coerceAtMost(originalQuantity) /
            originalQuantity
    }

    fun isStopHit(
        side: String,
        price: Double,
        stopLoss: Double
    ): Boolean = if (side == OrderSide.BUY.name) {
        price <= stopLoss
    } else {
        price >= stopLoss
    }

    fun isTargetHit(
        side: String,
        price: Double,
        target: Double
    ): Boolean = if (side == OrderSide.BUY.name) {
        price >= target
    } else {
        price <= target
    }

    fun stopExecutionPrice(
        side: String,
        quotePrice: Double,
        stopLoss: Double,
        slippageBps: Double
    ): Double {
        if (!quotePrice.isFinite() || quotePrice <= 0.0) return stopLoss
        return if (side == OrderSide.BUY.name) {
            if (quotePrice <= stopLoss) {
                quotePrice * (1.0 - slippageBps / 10_000.0)
            } else {
                stopLoss
            }
        } else {
            if (quotePrice >= stopLoss) {
                quotePrice * (1.0 + slippageBps / 10_000.0)
            } else {
                stopLoss
            }
        }
    }

    fun marketExecutionPrice(
        side: String,
        quotePrice: Double,
        slippageBps: Double
    ): Double = if (side == OrderSide.BUY.name) {
        quotePrice * (1.0 - slippageBps / 10_000.0)
    } else {
        quotePrice * (1.0 + slippageBps / 10_000.0)
    }
}
