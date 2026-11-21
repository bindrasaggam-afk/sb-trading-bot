package com.sb.ui.chart

import com.sb.backend.market.TimeframeBuckets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.Double.doubleToRawLongBits
import java.util.Calendar

class IndicatorIncrementalCacheTest {

    private val allIndicators = ChartIndicator.values().toSet()

    @Test
    fun appendMatchesFullRecalculation() {
        val candles = candles(70)
        val cache = IndicatorIncrementalCache()
        cache.update(candles, allIndicators)

        val appended = ArrayList(candles)
        appended.add(
            ChartCandle(
                timestamp = candles.last().timestamp + 60_000L,
                open = 171.5,
                high = 174.25,
                low = 170.75,
                close = 173.4,
                volume = 820L
            )
        )

        val incremental = cache.update(appended, allIndicators)
        val full = fullResult(appended, allIndicators)

        assertSameResult(full, incremental)
    }

    @Test
    fun liveUpdateOnLastCandleMatchesFullRecalculation() {
        val candles = ArrayList(candles(70))
        val cache = IndicatorIncrementalCache()
        cache.update(candles, allIndicators)

        val last = candles.last()
        candles[candles.lastIndex] = last.copy(
            high = last.high + 6.0,
            low = last.low - 4.0,
            close = last.close + 5.0,
            volume = last.volume + 500L
        )

        val incremental = cache.update(candles, allIndicators)
        val full = fullResult(candles, allIndicators)

        assertSameResult(full, incremental)
    }

    @Test
    fun unchangedSnapshotReusesCachedResult() {
        val candles = candles(60)
        val cache = IndicatorIncrementalCache()

        val first = cache.update(candles, allIndicators)
        val second = cache.update(ArrayList(candles), allIndicators.toSet())

        assertSame(first, second)
    }

    @Test
    fun historicalCorrectionForcesFullRecalculation() {
        val candles = ArrayList(candles(70))
        val cache = IndicatorIncrementalCache()
        val before = cache.update(candles, allIndicators)

        candles[30] = candles[30].copy(
            high = candles[30].high + 12.0,
            close = candles[30].close + 9.0,
            volume = candles[30].volume + 300L
        )

        val corrected = cache.update(candles, allIndicators)
        val full = fullResult(candles, allIndicators)

        assertSameResult(full, corrected)
        assertNotSame(before.sma50, corrected.sma50)
        assertNotSame(before.macd?.macd, corrected.macd?.macd)
    }

    @Test
    fun timeframeAndIndicatorSettingsInvalidateCache() {
        val candles = candles(60)
        val cache = IndicatorIncrementalCache()

        val initial = cache.update(candles, setOf(ChartIndicator.EMA_20), "1m")
        val otherTimeframe = cache.update(candles, setOf(ChartIndicator.EMA_20), "5m")
        assertSameResult(
            fullResult(candles, setOf(ChartIndicator.EMA_20)),
            otherTimeframe
        )
        assertNotSame(initial, otherTimeframe)

        val otherSettings = cache.update(candles, setOf(ChartIndicator.VWAP), "5m")
        assertSameResult(fullResult(candles, setOf(ChartIndicator.VWAP)), otherSettings)
        assertTrue(otherSettings.ema20.isEmpty())
        assertTrue(otherSettings.vwap.isNotEmpty())

        val backToInitial = cache.update(candles, setOf(ChartIndicator.EMA_20), "1m")
        assertSameResult(initial, backToInitial)
    }

    @Test
    fun vwapResetsAtSessionBoundary() {
        val firstSession = Calendar.getInstance(TimeframeBuckets.IST).apply {
            set(2026, Calendar.NOVEMBER, 10, 9, 15, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val secondSession = firstSession + 86_400_000L
        val candles = listOf(
            ChartCandle(timestamp = firstSession, open = 100.0, high = 104.0, low = 99.0, close = 102.0, volume = 100),
            ChartCandle(timestamp = firstSession + 60_000L, open = 103.0, high = 108.0, low = 102.0, close = 106.0, volume = 300),
            ChartCandle(timestamp = secondSession, open = 200.0, high = 206.0, low = 198.0, close = 204.0, volume = 50)
        )
        val cache = IndicatorIncrementalCache()

        val first = cache.update(candles.take(2), setOf(ChartIndicator.VWAP))
        val appended = cache.update(candles, setOf(ChartIndicator.VWAP))

        val expected = IndicatorMath.vwap(candles)
        assertSameDouble(expected[2], appended.vwap[2], "VWAP must reset on the new session")
        assertNotSame(first, appended)

        val live = ArrayList(candles)
        live[2] = live[2].copy(
            timestamp = secondSession + 60_000L,
            close = 220.0,
            high = 222.0,
            volume = 80L
        )
        val updated = cache.update(live, setOf(ChartIndicator.VWAP))
        val updatedExpected = IndicatorMath.vwap(live)
        assertSameDouble(updatedExpected[2], updated.vwap[2], "VWAP must update within the new session")
    }

    @Test
    fun allIndicatorsIncrementalMatchesFullAcrossAppendsAndLiveUpdates() {
        val candles = ArrayList(candles(55))
        val cache = IndicatorIncrementalCache()
        cache.update(candles, allIndicators, "5m")

        for (step in 0 until 12) {
            val last = candles.last()
            if (step % 3 == 0) {
                candles[candles.lastIndex] = last.copy(
                    high = last.high + 0.3 * (step + 1),
                    low = last.low - 0.2 * (step + 1),
                    close = last.close + 0.25 * (step + 1),
                    volume = last.volume + 17L
                )
            } else {
                val close = 140.0 + step * 1.75
                candles.add(
                    ChartCandle(
                        timestamp = last.timestamp + 60_000L,
                        open = close - 0.6,
                        high = close + 1.4,
                        low = close - 1.8,
                        close = close,
                        volume = 400L + step * 23L
                    )
                )
            }

            val incremental = cache.update(candles, allIndicators, "5m")
            val full = fullResult(candles, allIndicators)
            assertSameResult(full, incremental)
        }
    }

    @Test
    fun liveUpdateOnSingleCandleMatchesFullRecalculation() {
        val first = candles(1).first()
        val cache = IndicatorIncrementalCache()
        cache.update(listOf(first), allIndicators)

        val updated = listOf(
            first.copy(
                high = first.high + 4.0,
                low = first.low - 2.0,
                close = first.close + 3.0,
                volume = first.volume + 100L
            )
        )
        val incremental = cache.update(updated, allIndicators)

        assertSameResult(fullResult(updated, allIndicators), incremental)
    }

    @Test
    fun consecutiveLiveUpdatesMatchFullRecalculation() {
        val candles = ArrayList(candles(60))
        val cache = IndicatorIncrementalCache()
        cache.update(candles, allIndicators)

        repeat(4) { step ->
            val last = candles.last()
            candles[candles.lastIndex] = last.copy(
                high = last.high + 0.5 * (step + 1),
                low = last.low - 0.25 * (step + 1),
                close = last.close + 1.5 * (step + 1),
                volume = last.volume + 10L
            )
            val incremental = cache.update(candles, allIndicators)
            assertSameResult(fullResult(candles, allIndicators), incremental)
        }
    }

    @Test
    fun emptySnapshotToFirstCandleUsesFullRecalculation() {
        val cache = IndicatorIncrementalCache()
        val empty = cache.update(emptyList(), allIndicators)
        assertTrue(empty.ema20.isEmpty())
        assertTrue(empty.macd?.macd?.isEmpty() == true)

        val first = candles(1)
        val incremental = cache.update(first, allIndicators)

        assertSameResult(fullResult(first, allIndicators), incremental)
    }

    private fun candles(count: Int): List<ChartCandle> {
        val start = Calendar.getInstance(TimeframeBuckets.IST).apply {
            set(2026, Calendar.NOVEMBER, 10, 9, 15, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return (0 until count).map { index ->
            val close = 100.0 + index * 0.75 + (index % 7) * 0.4
            ChartCandle(
                timestamp = start + index * 60_000L,
                open = close - 0.5,
                high = close + 1.25 + (index % 3) * 0.15,
                low = close - 1.5 - (index % 4) * 0.1,
                close = close,
                volume = 250L + (index % 11) * 30L
            )
        }
    }

    private fun fullResult(
        candles: List<ChartCandle>,
        indicators: Set<ChartIndicator>
    ): IndicatorIncrementalCache.Result {
        val closes = candles.map { it.close }
        return IndicatorIncrementalCache.Result(
            ema20 = if (ChartIndicator.EMA_20 in indicators) {
                IndicatorMath.ema(closes, 20)
            } else DoubleArray(0),
            sma50 = if (ChartIndicator.SMA_50 in indicators) {
                IndicatorMath.sma(closes, 50)
            } else DoubleArray(0),
            vwap = if (ChartIndicator.VWAP in indicators) {
                IndicatorMath.vwap(candles)
            } else DoubleArray(0),
            rsi14 = if (ChartIndicator.RSI_14 in indicators) {
                IndicatorMath.rsi(closes, 14)
            } else DoubleArray(0),
            macd = if (ChartIndicator.MACD in indicators) {
                IndicatorMath.macd(closes, 12, 26, 9)
            } else null,
            bollinger = if (ChartIndicator.BOLLINGER in indicators) {
                IndicatorMath.bollinger(closes, 20, 2.0)
            } else null,
            supertrend = if (ChartIndicator.SUPERTREND in indicators) {
                IndicatorMath.supertrend(candles, 10, 3.0)
            } else null
        )
    }

    private fun assertSameResult(
        expected: IndicatorIncrementalCache.Result,
        actual: IndicatorIncrementalCache.Result
    ) {
        assertSameArray(expected.ema20, actual.ema20, "EMA20")
        assertSameArray(expected.sma50, actual.sma50, "SMA50")
        assertSameArray(expected.vwap, actual.vwap, "VWAP")
        assertSameArray(expected.rsi14, actual.rsi14, "RSI14")

        val expectedMacd = expected.macd
        val actualMacd = actual.macd
        assertTrue("MACD presence", (expectedMacd == null) == (actualMacd == null))
        if (expectedMacd != null && actualMacd != null) {
            assertSameArray(expectedMacd.macd, actualMacd.macd, "MACD")
            assertSameArray(expectedMacd.signal, actualMacd.signal, "MACD signal")
            assertSameArray(expectedMacd.histogram, actualMacd.histogram, "MACD histogram")
        }

        val expectedBollinger = expected.bollinger
        val actualBollinger = actual.bollinger
        assertTrue("Bollinger presence", (expectedBollinger == null) == (actualBollinger == null))
        if (expectedBollinger != null && actualBollinger != null) {
            assertSameArray(expectedBollinger.middle, actualBollinger.middle, "Bollinger middle")
            assertSameArray(expectedBollinger.upper, actualBollinger.upper, "Bollinger upper")
            assertSameArray(expectedBollinger.lower, actualBollinger.lower, "Bollinger lower")
        }

        val expectedSupertrend = expected.supertrend
        val actualSupertrend = actual.supertrend
        assertTrue("Supertrend presence", (expectedSupertrend == null) == (actualSupertrend == null))
        if (expectedSupertrend != null && actualSupertrend != null) {
            assertSameArray(expectedSupertrend.line, actualSupertrend.line, "Supertrend line")
            assertEquals(
                "Supertrend direction",
                expectedSupertrend.direction.toList(),
                actualSupertrend.direction.toList()
            )
        }
    }

    private fun assertSameArray(expected: DoubleArray, actual: DoubleArray, label: String) {
        assertEquals("$label length", expected.size, actual.size)
        for (index in expected.indices) {
            assertSameDouble(expected[index], actual[index], "$label[$index]")
        }
    }

    private fun assertSameDouble(expected: Double, actual: Double, label: String) {
        if (expected.isNaN() && actual.isNaN()) return
        assertEquals(label, doubleToRawLongBits(expected), doubleToRawLongBits(actual))
    }
}
