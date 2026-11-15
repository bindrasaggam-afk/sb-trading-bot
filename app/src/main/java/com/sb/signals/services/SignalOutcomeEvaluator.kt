package com.sb.signals.services

import com.sb.backend.market.model.MarketCandle
import com.sb.signals.domain.OutcomeResult
import com.sb.signals.domain.ResearchSignal
import com.sb.signals.domain.SignalDirection
import com.sb.signals.domain.SignalLifecycleStatus
import com.sb.signals.domain.StrategyConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object SignalOutcomeEvaluator {

    fun evaluate(
        signal: ResearchSignal,
        futureCandles: List<MarketCandle>,
        now: Long,
        config: StrategyConfig
    ): OutcomeResult? {
        if (!signal.status.isOpen) return null

        val candles = futureCandles
            .filter { it.timestamp > signal.candleTimestamp }
            .filter { it.timestamp <= now }
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }

        var status = signal.status
        var triggeredAt = signal.triggeredAt
        var target1At = signal.target1At
        var target2At = signal.target2At
        var target3At = signal.target3At
        var stoppedAt = signal.stoppedAt
        var closedAt = signal.closedAt
        var exitPrice = signal.exitPrice
        var ambiguousBar = signal.ambiguousBar
        var mfe = signal.maxFavorableExcursionPercent
        var mae = signal.maxAdverseExcursionPercent

        var triggered = triggeredAt != null ||
            status == SignalLifecycleStatus.TRIGGERED ||
            status == SignalLifecycleStatus.TARGET_1_HIT ||
            status == SignalLifecycleStatus.TARGET_2_HIT

        var entryIndex = -1
        if (triggered) {
            val triggerTime = triggeredAt ?: signal.candleTimestamp
            entryIndex = candles.indexOfFirst {
                it.timestamp >= triggerTime
            }
            if (entryIndex < 0) entryIndex = candles.size
        }
        for (index in candles.indices) {
            if (triggered) break
            val candle = candles[index]
            if (candle.timestamp > signal.expiresAt) break
            if (entryTouched(signal, candle)) {
                triggered = true
                triggeredAt = candle.timestamp
                status = SignalLifecycleStatus.TRIGGERED
                entryIndex = index
                break
            }
        }

        if (!triggered) {
            val expired = now >= signal.expiresAt ||
                (candles.isNotEmpty() &&
                    candles.last().timestamp > signal.expiresAt)
            if (!expired) {
                return OutcomeResult(
                    status = status,
                    eventTime = now,
                    exitPrice = null,
                    outcomeR = null,
                    outcomePnlPercent = null,
                    outcomeNetPnlPercent = null,
                    outcomeCostPercent = null,
                    reason = "Waiting for entry",
                    triggeredAt = null,
                    target1At = null,
                    target2At = null,
                    target3At = null,
                    stoppedAt = null,
                    closedAt = null,
                    mfePercent = null,
                    maePercent = null
                )
            }
            return OutcomeResult(
                status = SignalLifecycleStatus.EXPIRED,
                eventTime = signal.expiresAt,
                exitPrice = null,
                outcomeR = null,
                outcomePnlPercent = null,
                outcomeNetPnlPercent = null,
                outcomeCostPercent = null,
                reason = "Expired before entry",
                triggeredAt = null,
                target1At = null,
                target2At = null,
                target3At = null,
                stoppedAt = null,
                closedAt = signal.expiresAt,
                mfePercent = null,
                maePercent = null
            )
        }

        val entry = signal.referencePrice
        val risk = abs(entry - signal.stopLoss)
        var completed = false
        var expiryExit: Double? = null
        var lastCloseBeforeExpiry: Double? = null

        if (entryIndex >= 0) {
            for (index in entryIndex until candles.size) {
                val candle = candles[index]
                if (candle.timestamp > signal.expiresAt) {
                    expiryExit = lastCloseBeforeExpiry ?: candle.open
                    closedAt = signal.expiresAt
                    status = SignalLifecycleStatus.EXPIRED
                    exitPrice = expiryExit
                    completed = true
                    break
                }
                lastCloseBeforeExpiry = candle.close

                val stopTouched = when (signal.direction) {
                    SignalDirection.BUY -> candle.low <= signal.stopLoss
                    SignalDirection.SELL -> candle.high >= signal.stopLoss
                }
                val higherTarget = highestTargetHit(
                    signal = signal,
                    candle = candle
                )

                if (stopTouched) {
                    val gapThroughStop = when (signal.direction) {
                        SignalDirection.BUY -> candle.open <= signal.stopLoss
                        SignalDirection.SELL -> candle.open >= signal.stopLoss
                    }
                    if (higherTarget != null) ambiguousBar = true
                    stoppedAt = candle.timestamp
                    closedAt = candle.timestamp
                    status = SignalLifecycleStatus.STOPLOSS_HIT
                    exitPrice = if (gapThroughStop) {
                        candle.open
                    } else {
                        signal.stopLoss
                    }
                    completed = true
                    break
                }

                if (higherTarget != null) {
                    val t1Hit = targetHit(
                        signal.direction,
                        candle,
                        signal.target1
                    )
                    val t2Hit = targetHit(
                        signal.direction,
                        candle,
                        signal.target2
                    )
                    val t3Hit = targetHit(
                        signal.direction,
                        candle,
                        signal.target3
                    )
                    if (t1Hit) target1At = target1At ?: candle.timestamp
                    if (t2Hit) target2At = target2At ?: candle.timestamp
                    if (t3Hit) target3At = target3At ?: candle.timestamp
                    status = when {
                        t3Hit || target3At != null ->
                            SignalLifecycleStatus.TARGET_3_HIT
                        t2Hit || target2At != null ->
                            SignalLifecycleStatus.TARGET_2_HIT
                        else -> SignalLifecycleStatus.TARGET_1_HIT
                    }
                    exitPrice = when (status) {
                        SignalLifecycleStatus.TARGET_3_HIT -> signal.target3
                        SignalLifecycleStatus.TARGET_2_HIT -> signal.target2
                        else -> signal.target1
                    }
                    if (t3Hit) {
                        closedAt = candle.timestamp
                        completed = true
                    }
                }

                val highMove = when (signal.direction) {
                    SignalDirection.BUY -> (candle.high - entry) / entry * 100.0
                    SignalDirection.SELL -> (entry - candle.low) / entry * 100.0
                }
                val lowMove = when (signal.direction) {
                    SignalDirection.BUY -> (candle.low - entry) / entry * 100.0
                    SignalDirection.SELL -> (entry - candle.high) / entry * 100.0
                }
                mfe = max(mfe ?: highMove, highMove)
                mae = min(mae ?: lowMove, lowMove)

                if (completed) break
            }
        }

        if (!completed && now >= signal.expiresAt) {
            status = SignalLifecycleStatus.EXPIRED
            closedAt = signal.expiresAt
            exitPrice = lastCloseBeforeExpiry ?: candles.lastOrNull()?.close
            completed = true
        }

        if (!completed) {
            return OutcomeResult(
                status = status,
                eventTime = now,
                exitPrice = null,
                outcomeR = null,
                outcomePnlPercent = null,
                outcomeNetPnlPercent = null,
                outcomeCostPercent = null,
                reason = "Signal active",
                triggeredAt = triggeredAt,
                target1At = target1At,
                target2At = target2At,
                target3At = target3At,
                stoppedAt = stoppedAt,
                closedAt = null,
                ambiguousBar = ambiguousBar,
                mfePercent = mfe,
                maePercent = mae
            )
        }

        val exit = exitPrice
        val grossPnl = if (exit != null && entry > 0.0) {
            when (signal.direction) {
                SignalDirection.BUY -> (exit - entry) / entry * 100.0
                SignalDirection.SELL -> (entry - exit) / entry * 100.0
            }
        } else {
            null
        }
        val netPnl = grossPnl?.minus(config.totalCostPercent)
        val rMultiple = if (exit != null && risk > 0.0) {
            when (signal.direction) {
                SignalDirection.BUY -> (exit - entry) / risk
                SignalDirection.SELL -> (entry - exit) / risk
            }
        } else {
            null
        }

        val reason = when (status) {
            SignalLifecycleStatus.TARGET_3_HIT -> "Target 3 completed"
            SignalLifecycleStatus.STOPLOSS_HIT -> {
                if (ambiguousBar) {
                    "Stop-loss hit; target and stop touched in one candle"
                } else {
                    "Stop-loss hit"
                }
            }
            SignalLifecycleStatus.EXPIRED -> "Time expiry"
            else -> status.label
        }

        return OutcomeResult(
            status = status,
            eventTime = closedAt ?: now,
            exitPrice = exit,
            outcomeR = rMultiple,
            outcomePnlPercent = grossPnl,
            outcomeNetPnlPercent = netPnl,
            outcomeCostPercent = config.totalCostPercent,
            reason = reason,
            triggeredAt = triggeredAt,
            target1At = target1At,
            target2At = target2At,
            target3At = target3At,
            stoppedAt = stoppedAt,
            closedAt = closedAt,
            ambiguousBar = ambiguousBar,
            mfePercent = mfe,
            maePercent = mae
        )
    }

    private fun entryTouched(
        signal: ResearchSignal,
        candle: MarketCandle
    ): Boolean {
        return when (signal.direction) {
            SignalDirection.BUY ->
                candle.low <= signal.entryHigh &&
                    candle.high >= signal.entryLow
            SignalDirection.SELL ->
                candle.high >= signal.entryLow &&
                    candle.low <= signal.entryHigh
        }
    }

    private fun highestTargetHit(
        signal: ResearchSignal,
        candle: MarketCandle
    ): Double? {
        return when (signal.direction) {
            SignalDirection.BUY -> when {
                candle.high >= signal.target3 -> signal.target3
                candle.high >= signal.target2 -> signal.target2
                candle.high >= signal.target1 -> signal.target1
                else -> null
            }
            SignalDirection.SELL -> when {
                candle.low <= signal.target3 -> signal.target3
                candle.low <= signal.target2 -> signal.target2
                candle.low <= signal.target1 -> signal.target1
                else -> null
            }
        }
    }

    private fun targetHit(
        direction: SignalDirection,
        candle: MarketCandle,
        target: Double
    ): Boolean {
        return when (direction) {
            SignalDirection.BUY -> candle.high >= target
            SignalDirection.SELL -> candle.low <= target
        }
    }
}
