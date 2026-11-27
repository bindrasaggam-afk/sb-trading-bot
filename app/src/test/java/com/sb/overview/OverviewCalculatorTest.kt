package com.sb.overview

import com.sb.backend.models.Candle
import com.sb.backend.models.Quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class OverviewCalculatorTest {

    private val day = 86_400_000L
    private val base = 1_600_000_000_000L

    private fun series(
        count: Int,
        start: Double,
        step: Double,
        outlierIndex: Int? = null
    ): List<Candle> {
        return (0 until count).map { index ->
            val open = start + step * index
            val close = open + step
            var high = max(open, close) + 3.0
            var low = min(open, close) - 3.0

            if (index == outlierIndex) {
                high = 1_000.0
                low = 1.0
            }

            Candle(
                symbol = "TEST",
                timeframe = "1D",
                timestamp = base + index * day,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = 1_000L + index
            )
        }
    }

    private fun quote(
        ltp: Double,
        previousClose: Double? = null,
        isLive: Boolean = false,
        receivedAt: Long = base + 399 * day
    ): Quote {
        return Quote(
            symbol = "TEST",
            exchange = "NSE",
            ltp = ltp,
            previousClose = previousClose,
            volume = 5_000L,
            isLive = isLive,
            timestamp = receivedAt,
            receivedAt = receivedAt
        )
    }

    @Test
    fun week52RangeUsesOnlyTrailingDailyOhlc() {
        val candles = series(400, 100.0, 0.1, outlierIndex = 0)
        val snapshot = OverviewCalculator.calculate(
            candles = candles,
            quote = quote(ltp = 139.9, receivedAt = base + 399 * day),
            now = base + 399 * day
        )

        val stats = snapshot.stats
        assertNotNull(stats.weekHigh52)
        assertNotNull(stats.weekLow52)
        assertTrue(stats.weekHigh52!! < 200.0)
        assertTrue(stats.weekLow52!! > 50.0)
        assertTrue(stats.week52CoverageDays >= 360L)
        assertEquals(400, snapshot.dailyCandleCount)
    }

    @Test
    fun returnsAreNullWhenHistoryDoesNotCoverPeriod() {
        val shortHistory = series(20, 100.0, 0.5)
        val snapshot = OverviewCalculator.calculate(
            candles = shortHistory,
            quote = quote(ltp = 110.0, receivedAt = base + 19 * day),
            now = base + 19 * day
        )

        val returns = snapshot.returns.associate { it.label to it.percent }
        assertNotNull(returns["1W"])
        assertNull(returns["1M"])
        assertNull(returns["1Y"])
    }

    @Test
    fun quoteFallsBackToLastDailyCandleWhenQuoteMissing() {
        val candles = series(120, 100.0, 1.0)
        val snapshot = OverviewCalculator.calculate(
            candles = candles,
            quote = null,
            now = base + 119 * day
        )

        val stats = snapshot.stats
        assertEquals(OverviewDataState.HISTORICAL, snapshot.state)
        assertEquals(candles.last().close, stats.lastPrice!!, 0.0001)
        assertEquals(candles[candles.lastIndex - 1].close, stats.previousClose!!, 0.0001)
        assertNotNull(stats.change)
        assertTrue(stats.change!! > 0.0)
    }

    @Test
    fun liveQuoteProducesLiveState() {
        val candles = series(120, 100.0, 1.0)
        val now = base + 119 * day + 60_000L
        val snapshot = OverviewCalculator.calculate(
            candles = candles,
            quote = quote(
                ltp = 220.0,
                previousClose = 219.0,
                isLive = true,
                receivedAt = now - 30_000L
            ),
            now = now
        )

        assertEquals(OverviewDataState.LIVE, snapshot.state)
    }

    @Test
    fun staleQuoteProducesDelayedState() {
        val candles = series(120, 100.0, 1.0)
        val now = base + 119 * day + 3_600_000L
        val snapshot = OverviewCalculator.calculate(
            candles = candles,
            quote = quote(
                ltp = 220.0,
                previousClose = 219.0,
                isLive = false,
                receivedAt = now - 3_600_000L
            ),
            now = now
        )

        assertEquals(OverviewDataState.DELAYED, snapshot.state)
    }

    @Test
    fun shortHistoryWithoutQuoteProducesInsufficientDataState() {
        val candles = series(10, 100.0, 1.0)
        val snapshot = OverviewCalculator.calculate(
            candles = candles,
            quote = null,
            now = base + 9 * day
        )

        assertEquals(OverviewDataState.INSUFFICIENT_DATA, snapshot.state)
        assertNull(snapshot.stats.weekHigh52)
    }

    @Test
    fun noDataProducesNotAvailableState() {
        val snapshot = OverviewCalculator.calculate(
            candles = emptyList(),
            quote = null,
            now = base
        )

        assertEquals(OverviewDataState.NOT_AVAILABLE, snapshot.state)
        assertNull(snapshot.stats.lastPrice)
        assertNull(snapshot.stats.weekHigh52)
        assertEquals(0, snapshot.dailyCandleCount)
    }

    @Test
    fun risingSeriesProducesBullishTrend() {
        val candles = series(140, 100.0, 0.8)
        val snapshot = OverviewCalculator.calculate(
            candles = candles,
            quote = quote(
                ltp = candles.last().close + 5.0,
                receivedAt = base + 139 * day
            ),
            now = base + 139 * day
        )

        assertTrue(
            "Expected bullish trend but was ${snapshot.trend.label}",
            snapshot.trend.label == "Bullish"
        )
        assertTrue(snapshot.trend.bullishCount > snapshot.trend.bearishCount)
    }

    @Test
    fun fallingSeriesProducesBearishTrend() {
        val candles = series(140, 300.0, -0.8)
        val snapshot = OverviewCalculator.calculate(
            candles = candles,
            quote = quote(
                ltp = candles.last().close - 5.0,
                receivedAt = base + 139 * day
            ),
            now = base + 139 * day
        )

        assertTrue(
            "Expected bearish trend but was ${snapshot.trend.label}",
            snapshot.trend.label == "Bearish"
        )
        assertTrue(snapshot.trend.bearishCount > snapshot.trend.bullishCount)
    }

    @Test
    fun indicatorsAreNotFabricatedWithoutHistory() {
        val snapshot = OverviewCalculator.calculate(
            candles = emptyList(),
            quote = quote(ltp = 100.0),
            now = base
        )

        assertTrue(snapshot.indicators.isNotEmpty())
        assertTrue(snapshot.indicators.all {
            it.signal == OverviewSignal.NOT_AVAILABLE && it.value == "—"
        })
        assertEquals("Insufficient data", snapshot.trend.label)
    }
}
