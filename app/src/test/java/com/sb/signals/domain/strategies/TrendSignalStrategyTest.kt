package com.sb.signals.domain.strategies

import com.sb.backend.market.model.MarketCandle
import com.sb.signals.TestSignals
import com.sb.signals.domain.HoldingPeriod
import com.sb.signals.domain.SignalDirection
import com.sb.signals.domain.StrategyDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendSignalStrategyTest {

    @Test
    fun intradayUptrendProducesBuy() {
        val config = StrategyDefaults.intraday()
        val primary = realisticTrendCandles(
            count = 320,
            start = TestSignals.testNow - 320 * 300_000L,
            intervalMillis = 300_000L,
            startPrice = 100.0,
            step = 0.22
        )
        val confirmation = realisticTrendCandles(
            count = 120,
            start = TestSignals.testNow - 120 * 900_000L,
            intervalMillis = 900_000L,
            startPrice = 100.0,
            step = 0.30
        )
        val trend = realisticTrendCandles(
            count = 80,
            start = TestSignals.testNow - 80 * 3_600_000L,
            intervalMillis = 3_600_000L,
            startPrice = 100.0,
            step = 0.35
        )
        val candidate = TrendSignalStrategy(config).evaluate(
            StrategyInput(
                symbol = "RELIANCE",
                exchange = "NSE",
                now = TestSignals.testNow,
                primary = TestSignals.prepared(primary),
                confirmation = TestSignals.prepared(confirmation),
                trend = TestSignals.prepared(trend),
                config = config
            )
        )

        assertTrue(candidate != null)
        assertEquals(SignalDirection.BUY, candidate!!.direction)
        assertEquals(HoldingPeriod.ONE_DAY, candidate.holdingPeriod)
        assertTrue(candidate.score >= config.minScore)
        assertTrue(candidate.snapshot.confirmations.isNotEmpty())
    }

    private fun realisticTrendCandles(
        count: Int,
        start: Long,
        intervalMillis: Long,
        startPrice: Double,
        step: Double
    ): List<MarketCandle> {
        val phase = Math.PI / 2.0 - (count - 1) * 0.7
        return (0 until count).map { index ->
            val close = startPrice + step * index +
                kotlin.math.sin(index * 0.7 + phase) * 0.15
            val open = close - step * 0.35
            val volume = 1_000L + (index % 10) * 20L +
                if (index >= count - 3) 700L else 0L
            MarketCandle(
                timestamp = start + index * intervalMillis,
                open = open,
                high = maxOf(open, close) + 0.10,
                low = minOf(open, close) - 0.10,
                close = close,
                volume = volume
            )
        }
    }

    @Test
    fun swingSellRejectedForCashEquity() {
        val config = StrategyDefaults.swing()
        val primary = TestSignals.candles(
            count = 220,
            start = TestSignals.testNow - 220 * 86_400_000L,
            intervalMillis = 86_400_000L,
            startPrice = 200.0,
            step = -0.20
        )
        val confirmation = TestSignals.candles(
            count = 80,
            start = TestSignals.testNow - 80 * 86_400_000L,
            intervalMillis = 86_400_000L,
            startPrice = 200.0,
            step = -0.25
        )
        val candidate = TrendSignalStrategy(config).evaluate(
            StrategyInput(
                symbol = "RELIANCE",
                exchange = "NSE",
                now = TestSignals.testNow,
                primary = TestSignals.prepared(primary),
                confirmation = TestSignals.prepared(confirmation),
                trend = TestSignals.prepared(confirmation),
                config = config
            )
        )

        assertNull(candidate)
    }

    @Test
    fun deliveryNeverReturnsSell() {
        val config = StrategyDefaults.delivery()
        val primary = TestSignals.candles(
            count = 260,
            start = TestSignals.testNow - 260 * 86_400_000L,
            intervalMillis = 86_400_000L,
            startPrice = 200.0,
            step = -0.20
        )
        val confirmation = TestSignals.candles(
            count = 80,
            start = TestSignals.testNow - 80 * 86_400_000L,
            intervalMillis = 86_400_000L,
            startPrice = 200.0,
            step = -0.25
        )
        val candidate = TrendSignalStrategy(config).evaluate(
            StrategyInput(
                symbol = "RELIANCE",
                exchange = "NSE",
                now = TestSignals.testNow,
                primary = TestSignals.prepared(primary),
                confirmation = TestSignals.prepared(confirmation),
                trend = TestSignals.prepared(confirmation),
                config = config
            )
        )

        assertTrue(candidate == null || candidate.direction == SignalDirection.BUY)
    }
}