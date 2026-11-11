package com.sb.backend.market.analysis

import com.sb.backend.market.model.MarketCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalBacktesterTest {

    private fun candle(index: Int, open: Double, high: Double, low: Double, close: Double) =
        MarketCandle(
            timestamp = 1_700_000_000_000L + index * 60_000L,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = 100L
        )

    @Test fun entersOnNextCandleAndUsesConservativeStopWhenBothLevelsHit() {
        val candles = listOf(
            candle(0, 100.0, 101.0, 99.0, 100.0),
            candle(1, 100.0, 102.0, 99.0, 101.0),
            candle(2, 100.0, 106.0, 94.0, 102.0),
            candle(3, 102.0, 103.0, 101.0, 102.0)
        )
        var calls = 0

        val report = HistoricalBacktester.evaluateOutOfSample(
            candles = candles,
            outOfSampleStartIndex = 1,
            minimumHistory = 1,
            config = HistoricalBacktester.Config(
                feeBpsPerSide = 5.0,
                slippageBpsPerSide = 2.0,
                maxHoldingCandles = 3
            )
        ) { history ->
            calls++
            assertTrue(history.last().timestamp < candles[history.size].timestamp)
            HistoricalBacktester.Candidate(
                HistoricalBacktester.Direction.LONG,
                stopLoss = 95.0,
                target = 105.0
            )
        }

        assertEquals(1, report.trades.size)
        assertEquals(HistoricalBacktester.ExitReason.STOP_LOSS, report.trades.single().exitReason)
        assertEquals(HistoricalBacktester.Outcome.LOSS, report.trades.single().outcome)
        assertTrue(report.trades.single().entryPrice > 100.0)
        assertTrue(report.trades.single().exitPrice < 95.0)
        assertEquals(0.0, report.winRatePct, 0.0001)
        assertTrue(report.netReturnPct < 0.0)
        assertTrue(calls > 0)
    }

    @Test fun targetExitProducesWinAfterCosts() {
        val candles = listOf(
            candle(0, 100.0, 101.0, 99.0, 100.0),
            candle(1, 100.0, 102.0, 99.0, 101.0),
            candle(2, 100.0, 106.0, 99.0, 105.0),
            candle(3, 105.0, 106.0, 104.0, 105.0)
        )
        val report = HistoricalBacktester.evaluateOutOfSample(
            candles, 1, 1,
            HistoricalBacktester.Config(feeBpsPerSide = 1.0, slippageBpsPerSide = 1.0)
        ) {
            HistoricalBacktester.Candidate(HistoricalBacktester.Direction.LONG, 95.0, 104.0)
        }
        assertEquals(HistoricalBacktester.ExitReason.TARGET, report.trades.single().exitReason)
        assertEquals(HistoricalBacktester.Outcome.WIN, report.trades.single().outcome)
        assertTrue(report.profitFactor.isInfinite())
    }

    @Test fun noSignalsReturnsZeroCoverageWithoutInventingTrades() {
        val candles = (0..5).map { candle(it, 100.0, 101.0, 99.0, 100.0) }
        val report = HistoricalBacktester.evaluateOutOfSample(
            candles, 2, 2
        ) { null }
        assertTrue(report.trades.isEmpty())
        assertEquals(0.0, report.coveragePct, 0.0001)
        assertEquals(0.0, report.winRatePct, 0.0001)
    }
}
