package com.sb.signals.domain.strategies

import com.sb.signals.domain.HoldingPeriod
import com.sb.signals.domain.SignalCategory
import com.sb.signals.domain.SignalDirection
import com.sb.signals.domain.SignalLevels
import com.sb.signals.domain.StrategyConfig
import com.sb.signals.domain.market.MarketSession
import kotlin.math.max
import kotlin.math.min

data class PlannedSignal(
    val levels: SignalLevels,
    val referencePrice: Double,
    val riskPerUnit: Double,
    val rewardRisk1: Double,
    val rewardRisk2: Double,
    val rewardRisk3: Double,
    val expiresAt: Long
)

object SignalRiskPlanner {

    fun plan(
        direction: SignalDirection,
        close: Double,
        atr: Double,
        category: SignalCategory,
        holdingPeriod: HoldingPeriod,
        exchange: String,
        now: Long,
        config: StrategyConfig
    ): PlannedSignal? {
        if (close <= 0.0 || atr <= 0.0 || !close.isFinite() || !atr.isFinite()) {
            return null
        }

        val buffer = atr * config.entryAtrBuffer
        val entryLow: Double
        val entryHigh: Double
        val stopLoss: Double
        val target1: Double
        val target2: Double
        val target3: Double

        when (direction) {
            SignalDirection.BUY -> {
                entryLow = close - buffer
                entryHigh = close + buffer * 0.15
                stopLoss = min(
                    entryLow - atr * 0.25,
                    close - atr * config.stopAtrMultiplier
                )
                val risk = close - stopLoss
                if (risk <= 0.0) return null
                target1 = close + risk * config.targetR1
                target2 = close + risk * config.targetR2
                target3 = close + risk * config.targetR3
            }
            SignalDirection.SELL -> {
                entryHigh = close + buffer
                entryLow = close - buffer * 0.15
                stopLoss = max(
                    entryHigh + atr * 0.25,
                    close + atr * config.stopAtrMultiplier
                )
                val risk = stopLoss - close
                if (risk <= 0.0) return null
                target1 = close - risk * config.targetR1
                target2 = close - risk * config.targetR2
                target3 = close - risk * config.targetR3
            }
        }

        if (entryLow <= 0.0 || stopLoss <= 0.0 ||
            target1 <= 0.0 || target2 <= 0.0 || target3 <= 0.0
        ) {
            return null
        }

        val riskPerUnit = when (direction) {
            SignalDirection.BUY -> close - stopLoss
            SignalDirection.SELL -> stopLoss - close
        }
        val reward1 = when (direction) {
            SignalDirection.BUY -> target1 - close
            SignalDirection.SELL -> close - target1
        }
        val reward2 = when (direction) {
            SignalDirection.BUY -> target2 - close
            SignalDirection.SELL -> close - target2
        }
        val reward3 = when (direction) {
            SignalDirection.BUY -> target3 - close
            SignalDirection.SELL -> close - target3
        }

        val expiresAt = if (category == SignalCategory.INTRADAY) {
            MarketSession.sessionClose(now, exchange)
        } else {
            MarketSession.addDays(now, holdingPeriod.maxDays)
        }

        return PlannedSignal(
            levels = SignalLevels(
                entryLow = entryLow,
                entryHigh = entryHigh,
                stopLoss = stopLoss,
                target1 = target1,
                target2 = target2,
                target3 = target3
            ),
            referencePrice = close,
            riskPerUnit = riskPerUnit,
            rewardRisk1 = reward1 / riskPerUnit,
            rewardRisk2 = reward2 / riskPerUnit,
            rewardRisk3 = reward3 / riskPerUnit,
            expiresAt = expiresAt
        )
    }
}
