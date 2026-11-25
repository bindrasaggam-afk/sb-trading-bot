package com.sb.backend.services

import com.sb.backend.models.Quote
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveQuoteCacheAcceptanceTest {

    @After
    fun tearDown() {
        LiveQuoteCache.clear()
    }

    @Test
    fun rejectedTickDoesNotPublishQuoteOrNotifyListeners() {
        LiveQuoteCache.clear()
        var notifications = 0
        val listener: (Quote) -> Unit = { notifications++ }
        LiveQuoteCache.addListener(listener)

        try {
            val quote = quote(100.0)

            assertFalse(LiveQuoteCache.updateIfAccepted(quote, accepted = false))
            assertNull(LiveQuoteCache.get("SBIN", "NSE"))
            assertEquals(0, notifications)
        } finally {
            LiveQuoteCache.removeListener(listener)
        }
    }

    @Test
    fun acceptedTickPublishesQuoteAndNotifiesListeners() {
        LiveQuoteCache.clear()
        var notified: Quote? = null
        val listener: (Quote) -> Unit = { notified = it }
        LiveQuoteCache.addListener(listener)

        try {
            val quote = quote(101.0)

            assertTrue(LiveQuoteCache.updateIfAccepted(quote, accepted = true))
            assertSame(quote, LiveQuoteCache.get("SBIN", "NSE"))
            assertSame(quote, notified)
        } finally {
            LiveQuoteCache.removeListener(listener)
        }
    }

    private fun quote(price: Double) = Quote(
        symbol = "SBIN",
        exchange = "NSE",
        ltp = price,
        timestamp = 1_000L,
        receivedAt = 1_000L,
        isLive = true
    )
}
