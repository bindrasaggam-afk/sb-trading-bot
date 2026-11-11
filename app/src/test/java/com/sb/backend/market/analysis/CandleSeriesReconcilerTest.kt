package com.sb.backend.market.analysis

import com.sb.backend.market.model.MarketCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandleSeriesReconcilerTest {

    private fun c(t: Long, o: Double, h: Double, l: Double, close: Double, v: Long = 1L) =
        MarketCandle(t, o, h, l, close, v)

    @Test fun laterSourceReplacesWholeCandleWithoutSyntheticOhlc() {
        val stored = c(1_700_000_000_000L, 100.0, 110.0, 90.0, 105.0, 500L)
        val live = c(1_700_000_000_000L, 101.0, 108.0, 95.0, 107.0, 20L)

        val merged = CandleSeriesReconciler.merge(listOf(listOf(stored), listOf(live)))

        assertEquals(1, merged.size)
        assertEquals(live, merged.single())
    }

    @Test fun resultIsSortedDeduplicatedAndInvalidRowsAreDropped() {
        val result = CandleSeriesReconciler.merge(
            listOf(
                listOf(c(1_700_000_060_000L, 102.0, 103.0, 101.0, 102.5)),
                listOf(
                    c(1_700_000_000_000L, 100.0, 101.0, 99.0, 100.5),
                    c(1_700_000_060_000L, 102.0, 104.0, 101.0, 103.0),
                    c(1_700_000_120_000L, 100.0, 99.0, 101.0, 100.0)
                )
            )
        )

        assertEquals(listOf(1_700_000_000_000L, 1_700_000_060_000L), result.map { it.timestamp })
        assertEquals(103.0, result.last().close, 0.0001)
    }

    @Test fun limitKeepsNewestCandles() {
        val result = CandleSeriesReconciler.merge(
            listOf((1L..5L).map { c(1_700_000_000_000L + (it - 1L) * 60_000L, 100.0, 101.0, 99.0, 100.5) }),
            limit = 2
        )
        assertEquals(listOf(1_700_000_180_000L, 1_700_000_240_000L), result.map { it.timestamp })
        assertTrue(result.all { it.isValid })
    }
}
