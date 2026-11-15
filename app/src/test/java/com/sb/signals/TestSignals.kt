package com.sb.signals

import com.sb.backend.market.TimeframeBuckets
import com.sb.backend.market.model.MarketCandle
import com.sb.backend.models.Candle
import com.sb.signals.domain.DataQuality
import com.sb.signals.domain.HoldingPeriod
import com.sb.signals.domain.ResearchSignal
import com.sb.signals.domain.SignalCategory
import com.sb.signals.domain.SignalDirection
import com.sb.signals.domain.SignalIndicatorSnapshot
import com.sb.signals.domain.SignalLifecycleStatus
import com.sb.signals.domain.market.PreparedSeries
import java.util.Calendar

object TestSignals {

    val testNow: Long = Calendar.getInstance(
        TimeframeBuckets.IST
    ).apply {
        clear()
        set(2025, Calendar.JANUARY, 8, 10, 0, 0)
    }.timeInMillis

    fun candles(
        count: Int,
        start: Long,
        intervalMillis: Long,
        startPrice: Double = 100.0,
        step: Double = 0.25,
        volume: Long = 1_000L
    ): List<MarketCandle> {
        return (0 until count).map { index ->
            val close = startPrice + step * index
            val open = close - step * 0.4
            MarketCandle(
                timestamp = start + index * intervalMillis,
                open = open,
                high = close + 0.10,
                low = open - 0.10,
                close = close,
                volume = volume + index
            )
        }
    }

    fun prepared(
        values: List<MarketCandle>,
        countOverride: Int = values.size
    ): PreparedSeries {
        return PreparedSeries(
            all = values,
            closed = values,
            quality = DataQuality(
                score = 100.0,
                candleCount = countOverride,
                firstTimestamp = values.firstOrNull()?.timestamp,
                lastTimestamp = values.lastOrNull()?.timestamp
            )
        )
    }

    fun backendCandles(
        values: List<MarketCandle>
    ): List<Candle> {
        return values.map {
            Candle(
                symbol = "TEST",
                timeframe = "5m",
                timestamp = it.timestamp,
                open = it.open,
                high = it.high,
                low = it.low,
                close = it.close,
                volume = it.volume
            )
        }
    }

    fun signal(
        id: Long = 1L,
        direction: SignalDirection = SignalDirection.BUY,
        entryLow: Double = 99.0,
        entryHigh: Double = 101.0,
        reference: Double = 100.0,
        stop: Double = 98.0,
        target1: Double = 102.0,
        target2: Double = 104.0,
        target3: Double = 106.0,
        expiresAt: Long = testNow + 86_400_000L,
        status: SignalLifecycleStatus = SignalLifecycleStatus.NEW,
        triggeredAt: Long? = null,
        target1At: Long? = null,
        target2At: Long? = null,
        target3At: Long? = null,
        stoppedAt: Long? = null,
        closedAt: Long? = null,
        exitPrice: Double? = null,
        outcomeNet: Double? = null,
        outcomeR: Double? = null
    ): ResearchSignal {
        val risk = kotlin.math.abs(reference - stop)
        return ResearchSignal(
            id = id,
            symbol = "TEST",
            exchange = "NSE",
            category = SignalCategory.INTRADAY,
            direction = direction,
            timeframe = "5m",
            strategyId = "test-strategy",
            strategyVersion = "1.0.0",
            candleTimestamp = testNow - 3_600_000L,
            generatedAt = testNow - 3_500_000L,
            referencePrice = reference,
            entryLow = entryLow,
            entryHigh = entryHigh,
            stopLoss = stop,
            target1 = target1,
            target2 = target2,
            target3 = target3,
            riskPerUnit = risk,
            rewardRisk1 = kotlin.math.abs(target1 - reference) / risk,
            rewardRisk2 = kotlin.math.abs(target2 - reference) / risk,
            rewardRisk3 = kotlin.math.abs(target3 - reference) / risk,
            confidence = 80.0,
            holdingPeriod = HoldingPeriod.ONE_DAY,
            expiresAt = expiresAt,
            status = status,
            reasons = listOf("test"),
            indicatorSnapshot = SignalIndicatorSnapshot(
                values = mapOf(
                    "Close" to reference,
                    "ATR14" to 0.5
                ),
                confirmations = mapOf(
                    "EMA trend" to true,
                    "Higher timeframe" to true
                )
            ),
            dataQuality = DataQuality(
                score = 100.0,
                candleCount = 300,
                firstTimestamp = testNow - 86_400_000L,
                lastTimestamp = testNow
            ),
            triggeredAt = triggeredAt,
            target1At = target1At,
            target2At = target2At,
            target3At = target3At,
            stoppedAt = stoppedAt,
            closedAt = closedAt,
            exitPrice = exitPrice,
            outcomeNetPnlPercent = outcomeNet,
            outcomeR = outcomeR,
            lastEvaluatedAt = testNow
        )
    }
}
