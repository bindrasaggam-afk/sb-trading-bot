package com.sb.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartCandleSeriesTest {

    private fun candle(
        timestamp: Long,
        close: Double,
        open: Double = close - 1.0,
        high: Double = close + 1.0,
        low: Double = close - 2.0,
        volume: Long = 10L
    ) = ChartCandle(
        timestamp = timestamp,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume
    )

    @Test
    fun canonicalizeSortsAscendingAndKeepsLastDuplicate() {
        val older = candle(2_000L, 102.0)
        val newer = candle(2_000L, 103.0)
        val result = ChartCandleSeries.canonicalize(
            listOf(candle(3_000L, 104.0), older, candle(1_000L, 101.0), newer)
        )

        assertEquals(listOf(1_000L, 2_000L, 3_000L), result.map { it.timestamp })
        assertEquals(103.0, result[1].close, 0.000001)
    }

    @Test
    fun canonicalizeDropsInvalidRowsWithoutFabricatingValues() {
        val input = listOf(
            candle(1_000L, 101.0),
            candle(2_000L, 102.0, high = Double.NaN),
            candle(3_000L, 103.0, high = 100.0),
            candle(4_000L, 104.0, volume = -1L),
            candle(0L, 105.0),
            candle(5_000L, 106.0)
        )

        val result = ChartCandleSeries.canonicalize(input)

        assertEquals(listOf(1_000L, 5_000L), result.map { it.timestamp })
        assertEquals(101.0, result[0].close, 0.000001)
        assertEquals(106.0, result[1].close, 0.000001)
    }

    @Test
    fun isValidRejectsNonFiniteAndInvertedOhlc() {
        assertTrue(ChartCandleSeries.isValid(candle(1_000L, 100.0)))
        assertFalse(ChartCandleSeries.isValid(candle(1_000L, Double.NaN)))
        assertFalse(ChartCandleSeries.isValid(candle(1_000L, 100.0, open = -1.0)))
        assertFalse(ChartCandleSeries.isValid(candle(1_000L, 100.0, low = 200.0)))
        assertFalse(ChartCandleSeries.isValid(candle(1_000L, 100.0, volume = -5L)))
        assertFalse(ChartCandleSeries.isValid(candle(-1L, 100.0)))
    }
}
