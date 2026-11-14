package com.sb.ui.chart

import com.sb.backend.market.TimeframeBuckets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class IndicatorMathTest {

    @Test
    fun bollingerMiddleMatchesSma() {
        val values = (1..30).map { it.toDouble() }
        val result = IndicatorMath.bollinger(values, period = 20, multiplier = 2.0)
        val expected = values.subList(10, 30).average()

        assertEquals(expected, result.middle[29], 0.0001)
        assertTrue(result.upper[29] > result.middle[29])
        assertTrue(result.lower[29] < result.middle[29])
    }

    @Test
    fun bollingerMarksWarmupAsNan() {
        val values = (1..10).map { it.toDouble() }
        val result = IndicatorMath.bollinger(values, period = 20)

        assertTrue(result.middle.all { it.isNaN() })
        assertTrue(result.upper.all { it.isNaN() })
        assertTrue(result.lower.all { it.isNaN() })
    }

    @Test
    fun atrStartsAfterPeriod() {
        val candles = (1..20).map { index ->
            ChartCandle(
                open = index.toDouble(),
                high = index + 1.0,
                low = index - 1.0,
                close = index.toDouble()
            )
        }

        val atr = IndicatorMath.atr(candles, period = 14)

        for (index in 0 until 13) {
            assertTrue(atr[index].isNaN())
        }
        assertTrue(atr[13].isFinite())
    }

    @Test
    fun supertrendLineIsFiniteAfterWarmup() {
        val candles = (1..40).map { index ->
            ChartCandle(
                open = index.toDouble(),
                high = index + 2.0,
                low = index - 2.0,
                close = index + 1.0
            )
        }

        val result = IndicatorMath.supertrend(candles, period = 10, multiplier = 3.0)

        assertTrue(result.line.drop(9).any { it.isFinite() })
        assertTrue(result.direction.drop(9).all { it == 1 || it == -1 })
    }
    @Test
    fun macdSignalDoesNotTreatWarmupAsZeroPrices() {
        val values = (1..80).map { it.toDouble() }
        val result = IndicatorMath.macd(values)

        assertTrue(result.macd.take(24).all { it.isNaN() })
        assertTrue(result.macd[25].isFinite())
        assertTrue(result.signal.take(33).all { it.isNaN() })
        assertTrue(result.signal[33].isFinite())
        assertEquals(0.0, result.histogram[33], 0.000001)
    }

    @Test
    fun macdRejectsInvalidPeriodOrdering() {
        val result = IndicatorMath.macd((1..40).map { it.toDouble() }, fastPeriod = 26, slowPeriod = 12)
        assertTrue(result.macd.all { it.isNaN() })
        assertTrue(result.signal.all { it.isNaN() })
    }

    @Test
    fun flatMarketRsiIsNeutral() {
        val result = IndicatorMath.rsi(List(20) { 100.0 }, period = 14)
        assertEquals(50.0, result[14], 0.000001)
        assertEquals(50.0, result[19], 0.000001)
    }

    @Test
    fun vwapResetsAtNewIstSession() {
        val firstSession = java.util.Calendar.getInstance(TimeframeBuckets.IST).apply {
            set(2026, java.util.Calendar.NOVEMBER, 10, 10, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val secondSession = firstSession + 86_400_000L
        val candles = listOf(
            ChartCandle(timestamp = firstSession, open = 10.0, high = 10.0, low = 10.0, close = 10.0, volume = 100),
            ChartCandle(timestamp = firstSession + 60_000L, open = 20.0, high = 20.0, low = 20.0, close = 20.0, volume = 100),
            ChartCandle(timestamp = secondSession, open = 30.0, high = 30.0, low = 30.0, close = 30.0, volume = 100)
        )

        val result = IndicatorMath.vwap(candles)
        assertEquals(10.0, result[0], 0.000001)
        assertEquals(15.0, result[1], 0.000001)
        assertEquals(30.0, result[2], 0.000001)
    }

    @Test
    fun zeroVolumeVwapRemainsUnavailableUntilVolumeArrives() {
        val candles = listOf(
            ChartCandle(open = 10.0, high = 10.0, low = 10.0, close = 10.0, volume = 0),
            ChartCandle(open = 12.0, high = 12.0, low = 12.0, close = 12.0, volume = 5)
        )
        val result = IndicatorMath.vwap(candles)
        assertTrue(result[0].isNaN())
        assertEquals(12.0, result[1], 0.000001)
    }

    @Test
    fun emaRejectsNonFiniteInputInsteadOfPropagatingCorruption() {
        val result = IndicatorMath.ema(listOf(1.0, 2.0, Double.NaN, 4.0), 2)
        assertTrue(result.all { it.isNaN() })
    }


    @Test
    fun rsiRejectsNonFiniteInput() {
        val result = IndicatorMath.rsi(listOf(1.0, 2.0, Double.NaN, 4.0), 2)
        assertTrue(result.all { it.isNaN() })
    }

    @Test
    fun atrResetsWarmupAfterInvalidOhlc() {
        val candles = (1..8).map { index ->
            if (index == 4) {
                ChartCandle(open = 4.0, high = Double.NaN, low = 3.0, close = 4.0)
            } else {
                ChartCandle(open = index.toDouble(), high = index + 1.0, low = index - 1.0, close = index.toDouble())
            }
        }
        val result = IndicatorMath.atr(candles, period = 3)
        assertTrue(result[2].isFinite())
        assertTrue(result[3].isNaN())
        assertTrue(result[4].isNaN())
        assertTrue(result[5].isNaN())
        assertTrue(result[6].isFinite())
    }

    @Test
    fun bollingerRejectsInvalidMultiplier() {
        val result = IndicatorMath.bollinger((1..30).map { it.toDouble() }, 20, Double.NaN)
        assertTrue(result.upper.all { it.isNaN() })
        assertTrue(result.lower.all { it.isNaN() })
    }

    @Test
    fun vwapSkipsInvalidTypicalPriceWithoutPoisoningNextCandle() {
        val candles = listOf(
            ChartCandle(open = 10.0, high = 10.0, low = 10.0, close = 10.0, volume = 10),
            ChartCandle(open = 20.0, high = Double.POSITIVE_INFINITY, low = 20.0, close = 20.0, volume = 10),
            ChartCandle(open = 30.0, high = 30.0, low = 30.0, close = 30.0, volume = 10)
        )
        val result = IndicatorMath.vwap(candles)
        assertEquals(10.0, result[0], 0.000001)
        assertTrue(result[1].isNaN())
        assertEquals(20.0, result[2], 0.000001)
    }

}
