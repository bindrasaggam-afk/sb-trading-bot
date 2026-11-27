package com.sb.backend.market

import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.market.model.MarketCandle
import com.sb.backend.market.model.MarketTimeframe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketDataManagerRetentionTest {

    private val first = InstrumentKey("NSE", "SBIN")
    private val second = InstrumentKey("NSE", "RELIANCE")

    @After
    fun tearDown() {
        MarketDataManager.clear()
    }

    @Test
    fun inactiveLeastRecentlyUsedInstrumentIsEvictedFromAllCaches() {
        MarketDataManager.clear()
        MarketDataManager.seedCandles(first, listOf(candle(100.0)))
        MarketDataManager.tickFlow(first)
        MarketDataManager.seedCandles(second, listOf(candle(200.0)))
        MarketDataManager.tickFlow(second)

        MarketDataManager.trimInactiveCaches(maxEntries = 1)

        assertEquals(1, MarketDataManager.cachedInstrumentCount())
        assertTrue(MarketDataManager.getCandles(first, MarketTimeframe.MIN_1).isEmpty())
        assertEquals(1, MarketDataManager.getCandles(second, MarketTimeframe.MIN_1).size)
        assertEquals(1, MarketDataManager.cachedFlowCount())
    }

    @Test
    fun activeSubscriptionIsNeverEvictedEvenWhenSoftCapIsExceeded() {
        MarketDataManager.clear()
        MarketDataManager.seedCandles(first, listOf(candle(100.0)))
        MarketDataManager.seedCandles(second, listOf(candle(200.0)))
        MarketDataManager.subscribe(first)

        MarketDataManager.trimInactiveCaches(maxEntries = 1)

        assertEquals(1, MarketDataManager.cachedInstrumentCount())
        assertEquals(1, MarketDataManager.getCandles(first, MarketTimeframe.MIN_1).size)
        assertTrue(MarketDataManager.getCandles(second, MarketTimeframe.MIN_1).isEmpty())
        MarketDataManager.unsubscribe(first)
    }

    private fun candle(price: Double): MarketCandle = MarketCandle(
        timestamp = 1_762_000_000_000L,
        open = price,
        high = price + 1.0,
        low = price - 1.0,
        close = price,
        volume = 10L
    )
}
