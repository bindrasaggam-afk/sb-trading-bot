package com.sb.backend.market

import com.sb.backend.market.engine.CandleEngine
import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.market.model.MarketCandle
import com.sb.backend.market.model.MarketTick
import com.sb.backend.market.model.MarketTimeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.util.Calendar

class MarketDataManagerCandleAuthorityTest {

    private val key = InstrumentKey("NSE", "SBIN")

    @After
    fun tearDown() {
        MarketDataManager.clear()
    }

    @Test
    fun candleResultListenersObserveAuthoritativeEngine() {
        MarketDataManager.clear()
        val seen = ArrayList<MarketCandle>()
        val listener: (MarketTick, CandleEngine.TickResult) -> Unit = { _, result ->
            result.current(MarketTimeframe.MIN_1)?.let { seen.add(it) }
        }
        MarketDataManager.addCandleResultListener(listener)

        try {
            val first = MarketDataManager.onNewTick(tick(9, 15, 10, 100.0, 1_000L))
            val second = MarketDataManager.onNewTick(tick(9, 16, 10, 101.0, 1_100L))

            assertTrue(first.accepted)
            assertTrue(second.accepted)
            assertEquals(2, seen.size)
            assertEquals(
                MarketDataManager.getCandles(key, MarketTimeframe.MIN_1).last(),
                seen.last()
            )
        } finally {
            MarketDataManager.removeCandleResultListener(listener)
        }
    }

    private fun tick(
        hour: Int,
        minute: Int,
        second: Int,
        ltp: Double,
        volume: Long
    ): MarketTick {
        val timestamp = Calendar.getInstance(TimeframeBuckets.IST).apply {
            clear()
            set(2026, Calendar.JANUARY, 5, hour, minute, second)
        }.timeInMillis

        return MarketTick(
            key = key,
            ltp = ltp,
            volume = volume,
            lastTradeQty = 1L,
            lastTradeTime = timestamp,
            receivedAt = timestamp
        )
    }
}
