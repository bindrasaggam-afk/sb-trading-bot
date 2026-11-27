package com.sb.backend.market.analysis

import com.sb.backend.market.TimeframeBuckets
import com.sb.backend.market.model.MarketCandle
import com.sb.backend.market.model.MarketTimeframe
import com.sb.backend.models.Candle
import com.sb.signals.domain.market.CandleSeriesTools
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeframeAggregatorTest {

    private fun baseTimestamp(): Long {
        val calendar = Calendar.getInstance(TimeframeBuckets.IST).apply {
            clear()
            set(2025, Calendar.JANUARY, 8, 9, 15, 0)
        }
        return calendar.timeInMillis
    }

    private fun oneMinuteCandles(
        count: Int = 60,
        startIndex: Int = 0,
        base: Long = baseTimestamp()
    ): List<Candle> = (startIndex until startIndex + count).map { index ->
        val open = 100.0 + index
        Candle(
            symbol = "TEST",
            exchange = "NSE",
            timeframe = "1m",
            timestamp = base + (index - startIndex) * 60_000L,
            open = open,
            high = open + 2.0,
            low = open - 1.0,
            close = open + 1.0,
            volume = 10L + index
        )
    }

    private fun toMarketCandles(candles: List<Candle>): List<MarketCandle> =
        candles.mapNotNull { CandleSeriesTools.toMarketCandle(it) }

    @Test
    fun oneMinuteChainMatchesDirectOneHourAndCandleSeriesTools() {
        val oneMinute = oneMinuteCandles()
        val base = baseTimestamp()

        val fiveMinute = TimeframeAggregator.aggregate(
            source = oneMinute,
            targetTimeframe = MarketTimeframe.MIN_5,
            targetLabel = "5m",
            exchange = "NSE"
        )
        assertEquals(12, fiveMinute.size)
        assertEquals(base, fiveMinute.first().timestamp)
        assertEquals("5m", fiveMinute.first().timeframe)

        val fifteenMinute = TimeframeAggregator.aggregate(
            source = fiveMinute,
            targetTimeframe = MarketTimeframe.MIN_15,
            targetLabel = "15m",
            exchange = "NSE"
        )
        assertEquals(4, fifteenMinute.size)
        assertEquals(base, fifteenMinute.first().timestamp)

        val hourly = TimeframeAggregator.aggregate(
            source = fifteenMinute,
            targetTimeframe = MarketTimeframe.HOUR_1,
            targetLabel = "1h",
            exchange = "NSE"
        )
        assertEquals(1, hourly.size)

        val hour = hourly.single()
        assertEquals(base, hour.timestamp)
        assertEquals("1h", hour.timeframe)
        assertEquals(100.0, hour.open, 0.0)
        assertEquals(161.0, hour.high, 0.0)
        assertEquals(99.0, hour.low, 0.0)
        assertEquals(160.0, hour.close, 0.0)
        assertEquals(2_370L, hour.volume)

        val direct = TimeframeAggregator.aggregate(
            source = oneMinute,
            targetTimeframe = MarketTimeframe.HOUR_1,
            targetLabel = "1h",
            exchange = "NSE"
        )
        assertEquals(hourly, direct)

        val fromCandleSeriesTools = CandleSeriesTools.aggregate(
            source = toMarketCandles(oneMinute),
            targetLabel = "1h",
            exchange = "NSE"
        )
        val report = CandleSeriesAudit.compare(
            stored = toMarketCandles(hourly),
            live = fromCandleSeriesTools
        )
        assertTrue(report.exactMatch)
        assertEquals(1, report.matchedTimestamps)
    }

    @Test
    fun secondsAreNormalizedAndNonPositiveTimestampsAreSkipped() {
        val base = baseTimestamp()
        val secondsCandle = oneMinuteCandles(count = 1, base = base).single()
            .copy(timestamp = (base + 60_000L) / 1_000L)
        val invalidCandles = listOf(
            secondsCandle.copy(timestamp = 0L),
            secondsCandle.copy(timestamp = -1L)
        )

        val result = TimeframeAggregator.aggregate(
            source = invalidCandles + secondsCandle,
            targetTimeframe = MarketTimeframe.MIN_5,
            targetLabel = "5m",
            exchange = "NSE"
        )

        assertEquals(1, result.size)
        assertEquals(
            TimeframeBuckets.bucketStart(
                timestamp = base + 60_000L,
                timeframe = MarketTimeframe.MIN_5,
                exchange = "NSE"
            ),
            result.single().timestamp
        )
        assertEquals(secondsCandle.volume, result.single().volume)
    }

    @Test
    fun unsortedSourceUsesTimestampOrderForCloseAndMatchesSeriesTools() {
        val base = baseTimestamp()
        val candles = oneMinuteCandles(count = 3, base = base)
        val unsorted = listOf(candles[2], candles[0], candles[1])

        val result = TimeframeAggregator.aggregate(
            source = unsorted,
            targetTimeframe = MarketTimeframe.MIN_5,
            targetLabel = "5m",
            exchange = "NSE"
        )

        assertEquals(1, result.size)
        assertEquals(candles[2].close, result.single().close, 0.0)
        assertEquals(candles.sumOf { it.volume }, result.single().volume)

        val fromCandleSeriesTools = CandleSeriesTools.aggregate(
            source = toMarketCandles(unsorted),
            targetLabel = "5m",
            exchange = "NSE"
        )
        val report = CandleSeriesAudit.compare(
            stored = toMarketCandles(result),
            live = fromCandleSeriesTools
        )
        assertTrue(report.exactMatch)
    }
}
