package com.sb.signals.domain.market

import com.sb.backend.market.TimeframeBuckets
import com.sb.backend.market.model.MarketCandle
import com.sb.backend.models.Candle
import com.sb.signals.TestSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandleSeriesToolsTest {

    @Test
    fun aggregateBuildsAlignedHigherTimeframeCandles() {
        val start = dayMillis(9, 15)
        val source = listOf(
            candle(start, 100.0, 101.0, 99.0, 100.5, 10),
            candle(start + 300_000L, 100.5, 102.0, 100.0, 101.0, 20),
            candle(start + 600_000L, 101.0, 101.5, 98.0, 99.0, 30),
            candle(start + 900_000L, 99.0, 100.0, 98.5, 99.5, 40)
        )
        val aggregated = CandleSeriesTools.aggregate(
            source,
            "15m",
            "NSE"
        )
        assertEquals(2, aggregated.size)
        assertEquals(start, aggregated[0].timestamp)
        assertEquals(100.0, aggregated[0].open, 0.000001)
        assertEquals(102.0, aggregated[0].high, 0.000001)
        assertEquals(98.0, aggregated[0].low, 0.000001)
        assertEquals(99.0, aggregated[0].close, 0.000001)
        assertEquals(60L, aggregated[0].volume)
        assertEquals(start + 900_000L, aggregated[1].timestamp)
    }

    @Test
    fun prepareDropsCurrentFormingCandle() {
        val start = dayMillis(9, 50)
        val raw = listOf(
            candleObject(start, 100.0, 5L),
            candleObject(start + 300_000L, 101.0, 5L),
            candleObject(start + 600_000L, 102.0, 5L)
        )
        val prepared = CandleSeriesTools.prepare(
            raw = raw,
            timeframe = "5m",
            exchange = "NSE",
            now = dayMillis(10, 0),
            minCount = 2
        )
        assertEquals(2, prepared.closed.size)
        assertEquals(start + 300_000L, prepared.closed.last().timestamp)
        assertTrue(prepared.quality.isUsable)
    }

    @Test
    fun prepareDropsTodayDailyCandle() {
        val yesterday = dayMillis(0, 0) - 86_400_000L
        val today = dayMillis(0, 0)
        val raw = listOf(
            candleObject(yesterday, 100.0, 5L),
            candleObject(today, 101.0, 5L)
        )
        val prepared = CandleSeriesTools.prepare(
            raw = raw,
            timeframe = "1D",
            exchange = "NSE",
            now = dayMillis(10, 0),
            minCount = 1
        )
        assertEquals(1, prepared.closed.size)
        assertEquals(yesterday, prepared.closed.last().timestamp)
    }

    @Test
    fun duplicateAndInvalidCandlesReduceQuality() {
        val start = dayMillis(9, 50)
        val raw = listOf(
            candleObject(start, 100.0, 5L),
            candleObject(start, 101.0, 5L),
            Candle(
                symbol = "X",
                timeframe = "5m",
                timestamp = start + 300_000L,
                open = 0.0,
                high = 1.0,
                low = 0.0,
                close = 1.0,
                volume = 5L
            )
        )
        val prepared = CandleSeriesTools.prepare(
            raw = raw,
            timeframe = "5m",
            exchange = "NSE",
            now = dayMillis(10, 0),
            minCount = 1
        )
        assertEquals(1, prepared.closed.size)
        assertTrue(prepared.quality.score < 100.0)
        assertFalse(prepared.quality.notes.isEmpty())
    }

    private fun candle(
        timestamp: Long,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Long
    ): MarketCandle {
        return MarketCandle(
            timestamp = timestamp,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume
        )
    }

    private fun candleObject(
        timestamp: Long,
        close: Double,
        volume: Long
    ): Candle {
        return Candle(
            symbol = "TEST",
            timeframe = "5m",
            timestamp = timestamp,
            open = close - 0.5,
            high = close + 0.5,
            low = close - 1.0,
            close = close,
            volume = volume
        )
    }

    private fun dayMillis(hour: Int, minute: Int): Long {
        return java.util.Calendar.getInstance(
            TimeframeBuckets.IST
        ).apply {
            clear()
            set(2025, java.util.Calendar.JANUARY, 8, hour, minute, 0)
        }.timeInMillis
    }
}
