package com.sb.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
