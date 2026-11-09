package com.sb.backend.repositories

import org.junit.Assert.assertEquals
import org.junit.Test

class MarketRepositoryTimeframeTest {

    @Test
    fun minuteAndCalendarMonthLabelsRemainDistinct() {
        assertEquals("1m", normalizeCandleTimeframe("1m"))
        assertEquals("1M", normalizeCandleTimeframe("1M"))
        assertEquals("1m", normalizeCandleTimeframe(" 1m "))
        assertEquals("1M", normalizeCandleTimeframe(" 1M "))
    }

    @Test
    fun timeframeNormalizationDoesNotChangeCase() {
        assertEquals("5m", normalizeCandleTimeframe("5m"))
        assertEquals("15m", normalizeCandleTimeframe("15m"))
        assertEquals("1D", normalizeCandleTimeframe("1D"))
        assertEquals("1W", normalizeCandleTimeframe("1W"))
    }
}
