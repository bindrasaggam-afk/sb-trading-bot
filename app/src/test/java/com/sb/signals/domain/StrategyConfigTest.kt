package com.sb.signals.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyConfigTest {

    @Test
    fun intradayVariantsCoverRequiredTimeframes() {
        val variants = StrategyDefaults.intradayVariants()

        assertEquals(
            listOf("5m"),
            variants.map { it.primaryTimeframe }
        )
        assertEquals(
            variants.size,
            variants.map { it.strategyId }.distinct().size
        )
        assertTrue(
            variants.all {
                it.category == SignalCategory.INTRADAY &&
                    it.version == "1.2.0"
            }
        )
    }

    @Test
    fun fiveMinuteVariantKeepsBackwardCompatibleIdentity() {
        val fiveMinute = StrategyDefaults.intraday("5m")

        assertEquals("intraday-trend-breakout", fiveMinute.strategyId)
        assertEquals("5m", fiveMinute.primaryTimeframe)
        assertEquals("15m", fiveMinute.confirmationTimeframe)
        assertEquals("1h", fiveMinute.trendTimeframe)
    }

    @Test
    fun intradayHigherTimeframesAreAbovePrimary() {
        val variants = StrategyDefaults.intradayVariants()
        assertEquals(1, variants.size)
        assertEquals("5m", variants.single().primaryTimeframe)
        assertEquals("15m", variants.single().confirmationTimeframe)
        assertEquals("1h", variants.single().trendTimeframe)
    }

    @Test
    fun deliveryUsesFourToFiveMonthWindow() {
        val delivery = StrategyDefaults.delivery()

        assertEquals(
            HoldingPeriod.FIVE_MONTHS,
            delivery.deliveryHoldingPeriod
        )
        assertTrue(
            HoldingPeriod.FOUR_MONTHS.maxDays >= 120
        )
        assertTrue(
            HoldingPeriod.FIVE_MONTHS.maxDays >= 150
        )
        assertEquals(
            HoldingPeriod.FOUR_MONTHS.maxDays + 1,
            HoldingPeriod.FIVE_MONTHS.minDays
        )
        assertEquals(
            HoldingPeriod.FIVE_MONTHS.maxDays + 1,
            HoldingPeriod.SIX_MONTHS.minDays
        )
    }

    @Test
    fun swingAndDeliveryUseWeeklyConfirmationAndMonthlyTrend() {
        val swing = StrategyDefaults.swing()
        val delivery = StrategyDefaults.delivery()
        assertEquals("1W", swing.confirmationTimeframe)
        assertEquals("1M", swing.trendTimeframe)
        assertEquals("1W", delivery.confirmationTimeframe)
        assertEquals("1M", delivery.trendTimeframe)
        assertEquals(60, swing.minConfirmationCandles)
        assertEquals(60, swing.minTrendCandles)
        // Delivery intentionally uses a lower historical sample requirement to
        // avoid requiring five years of monthly candles for signal eligibility.
        assertEquals(30, delivery.minConfirmationCandles)
        assertEquals(30, delivery.minTrendCandles)
        assertEquals("1.2.0", swing.version)
        assertEquals("1.1.0", delivery.version)
    }

    @Test
    fun rejectsNonFiniteRiskConfiguration() {
        val config = StrategyDefaults.intraday()
        var rejected = false
        try {
            config.copy(entryAtrBuffer = Double.NaN)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun defaultCatalogContainsEveryCategory() {
        val configs = StrategyDefaults.all()

        assertEquals(3, configs.size)
        assertEquals(
            SignalCategory.values().toSet(),
            configs.map { it.category }.toSet()
        )
    }
}
