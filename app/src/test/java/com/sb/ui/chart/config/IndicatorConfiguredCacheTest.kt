package com.sb.ui.chart.config

import com.sb.ui.chart.ChartCandle
import com.sb.ui.chart.ChartIndicator
import com.sb.ui.chart.IndicatorIncrementalCache
import com.sb.ui.chart.IndicatorMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IndicatorConfiguredCacheTest {

    @Test
    fun customPeriodAndSourceReplaceHardcodedDefaults() {
        val candles = candles(40)
        val config = IndicatorConfigPolicy.DEFAULT.copy(
            enabled = setOf(ChartIndicator.EMA_20),
            timeframeKey = "1m",
            source = IndicatorSource.HIGH,
            emaPeriod = 3
        )

        val result = IndicatorIncrementalCache().update(candles, config)
        val expected = IndicatorMath.ema(candles.map { it.high }, 3)

        assertArrayEquals(expected, result.ema20, 1e-9)
    }

    @Test
    fun legacyUpdateStillMatchesDefaultAndConfigChangeForcesRecalculation() {
        val candles = candles(60)
        val closes = candles.map { it.close }
        val cache = IndicatorIncrementalCache()

        val legacy = cache.update(candles, setOf(ChartIndicator.EMA_20), "1m")
        assertArrayEquals(IndicatorMath.ema(closes, 20), legacy.ema20, 1e-9)

        val custom = IndicatorConfigPolicy.DEFAULT.copy(
            enabled = setOf(ChartIndicator.EMA_20),
            timeframeKey = "1m",
            emaPeriod = 5
        )
        val configured = cache.update(candles, custom)

        assertArrayEquals(IndicatorMath.ema(closes, 5), configured.ema20, 1e-9)
        assertFalse(configured.ema20.contentEquals(legacy.ema20))
        assertEquals(candles.size, cache.lastResult.ema20.size)
    }

    @Test
    fun disabledIndicatorsReturnEmptyArrays() {
        val candles = candles(30)
        val config = IndicatorConfigPolicy.DEFAULT.copy(
            enabled = setOf(ChartIndicator.VWAP),
            timeframeKey = "5m"
        )

        val result = IndicatorIncrementalCache().update(candles, config)

        assertEquals(0, result.ema20.size)
        assertEquals(0, result.sma50.size)
        assertEquals(candles.size, result.vwap.size)
    }

    private fun candles(count: Int): List<ChartCandle> = (1..count).map { index ->
        ChartCandle(
            timestamp = index * 60_000L,
            open = 100.0 + index,
            high = 110.0 + index,
            low = 95.0 + index,
            close = 105.0 + index * 0.5,
            volume = 100L + index
        )
    }
}
