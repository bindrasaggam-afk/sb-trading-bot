package com.sb.backend.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiRateLimiterTest {

    @Test
    fun burstIsConsumedBeforeRejection() {
        val limiter = ApiRateLimiter(
            requestsPerMinute = 60,
            burst = 2
        )

        assertNull(limiter.acquire("127.0.0.1", 1_000L))
        assertNull(limiter.acquire("127.0.0.1", 1_000L))
        assertNotNull(limiter.acquire("127.0.0.1", 1_000L))
    }

    @Test
    fun tokensRefillOverTime() {
        val limiter = ApiRateLimiter(
            requestsPerMinute = 60,
            burst = 1
        )

        assertNull(limiter.acquire("client", 10_000L))
        assertNotNull(limiter.acquire("client", 10_999L))
        assertNull(limiter.acquire("client", 11_000L))
    }

    @Test
    fun retryAfterIsReportedInSeconds() {
        val limiter = ApiRateLimiter(
            requestsPerMinute = 60,
            burst = 1
        )

        assertNull(limiter.acquire("client", 1_000L))
        val retryAfter = limiter.acquire("client", 1_000L)

        assertNotNull(retryAfter)
        assertTrue(retryAfter!! in 1L..2L)
    }

    @Test
    fun clientsHaveIndependentBuckets() {
        val limiter = ApiRateLimiter(
            requestsPerMinute = 60,
            burst = 1
        )

        assertNull(limiter.acquire("one", 1_000L))
        assertNotNull(limiter.acquire("one", 1_000L))
        assertNull(limiter.acquire("two", 1_000L))
    }

    @Test
    fun zeroRateFallsBackToOneMinuteRetry() {
        val limiter = ApiRateLimiter(
            requestsPerMinute = 0,
            burst = 1
        )

        assertNull(limiter.acquire("client", 1_000L))
        assertEquals(60L, limiter.acquire("client", 1_000L))
    }
}
