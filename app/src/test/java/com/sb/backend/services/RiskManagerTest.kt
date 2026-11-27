package com.sb.backend.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskManagerTest {

    private val riskManager = RiskManager()

    @Test
    fun validateOrder_rejectsQuantityAboveMaximum() {
        val decision = riskManager.validateOrder(
            side = "BUY",
            quantity = 100_001,
            price = 1.0,
            availableBalance = 1_000_000.0,
            openPositions = 0
        )

        assertFalse(decision.allowed)
        assertTrue(decision.reason.orEmpty().contains("maximum", ignoreCase = true))
    }

    @Test
    fun validateOrder_acceptsQuantityAtMaximum() {
        val decision = riskManager.validateOrder(
            side = "BUY",
            quantity = 100_000,
            price = 1.0,
            availableBalance = 1_000_000.0,
            openPositions = 0
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun isQuoteFresh_rejectsOldTimestamp() {
        val now = System.currentTimeMillis()

        assertFalse(riskManager.isQuoteFresh(now - 10_000L, maxAgeMs = 5_000L))
    }

    @Test
    fun isQuoteFresh_acceptsRecentTimestamp() {
        val now = System.currentTimeMillis()

        assertTrue(riskManager.isQuoteFresh(now, maxAgeMs = 5_000L))
    }
}
