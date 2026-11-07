package com.sb.backend.market.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MarketModelsTest {

    @Test
    fun instrumentKeyUsesLocaleIndependentUppercase() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertEquals("NSE|SBIN", InstrumentKey(" nse ", " sbin ").key())
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun normalizeMillisConvertsSecondsAndKeepsMilliseconds() {
        assertNull(MarketTick.normalizeMillis(null))
        assertNull(MarketTick.normalizeMillis(0L))
        assertNull(MarketTick.normalizeMillis(-5L))
        assertEquals(1_700_000_000_000L, MarketTick.normalizeMillis(1_700_000_000L))
        assertEquals(1_700_000_000_000L, MarketTick.normalizeMillis(1_700_000_000_000L))
    }

    @Test
    fun marketCandleAcceptsWellFormedCandle() {
        val candle = MarketCandle.create(
            timestamp = 1_700_000_000_000L,
            open = 100.0,
            high = 105.0,
            low = 99.0,
            close = 104.0,
            volume = 10L
        )

        assertNotNull(candle)
        assertTrue(candle!!.isValid)
    }

    @Test
    fun marketCandleRejectsBrokenOhlc() {
        assertFalse(validCandle().copy(high = 98.0).isValid)
        assertFalse(validCandle().copy(high = 103.0, close = 104.0).isValid)
        assertFalse(validCandle().copy(low = 101.0).isValid)
        assertFalse(validCandle().copy(timestamp = 0L).isValid)
        assertFalse(validCandle().copy(volume = -1L).isValid)
    }

    @Test
    fun marketCandleRejectsNonFinitePrices() {
        assertFalse(validCandle().copy(open = Double.NaN).isValid)
        assertFalse(validCandle().copy(close = Double.POSITIVE_INFINITY).isValid)
        assertNull(
            MarketCandle.create(
                timestamp = 1_700_000_000_000L,
                open = 0.0,
                high = 1.0,
                low = 0.0,
                close = 1.0
            )
        )
    }

    @Test
    fun timeframeClassificationCoversEveryValue() {
        for (timeframe in MarketTimeframe.values()) {
            assertTrue(
                "${timeframe.label} must be intraday or calendar",
                timeframe.isIntraday != timeframe.usesCalendarBuckets
            )
        }

        assertTrue(MarketTimeframe.MIN_1.isIntraday)
        assertTrue(MarketTimeframe.HOUR_4.isIntraday)
        assertFalse(MarketTimeframe.MIN_1.usesCalendarBuckets)

        assertTrue(MarketTimeframe.DAY_1.usesCalendarBuckets)
        assertTrue(MarketTimeframe.WEEK_4.usesCalendarBuckets)
        assertTrue(MarketTimeframe.MONTH_1.usesCalendarBuckets)
        assertTrue(MarketTimeframe.YEAR_5.usesCalendarBuckets)
        assertFalse(MarketTimeframe.MONTH_1.isIntraday)
    }

    private fun validCandle(): MarketCandle = MarketCandle(
        timestamp = 1_700_000_000_000L,
        open = 100.0,
        high = 105.0,
        low = 99.0,
        close = 104.0,
        volume = 10L
    )
}
