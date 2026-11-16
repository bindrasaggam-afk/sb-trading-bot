package com.sb.signals.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyConfigTest {

    @Test
    fun intradayVariantsCoverRequiredTimeframes() {
        val variants = StrategyDefaults.intradayVariants()

        assertEquals(
            listOf("1m", "3m", "5m", "10m", "15m", "30m"),
            variants.map { it.primaryTimeframe }
        )
        assertEquals(
            variants.size,
            variants.map { it.strategyId }.distinct().size
        )
        assertTrue(
            variants.all {
                it.category == SignalCategory.INTRADAY &&
                    it.version == "1.1.0"
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

        variants.forEach { config ->
            assertTrue(
                config.primaryTimeframe != config.confirmationTimeframe
            )
            assertTrue(
                config.confirmationTimeframe != config.trendTimeframe
            )
        }
        assertEquals(
            "5m",
            variants.first { it.primaryTimeframe == "1m" }
                .confirmationTimeframe
        )
        assertEquals(
            "15m",
            variants.first { it.primaryTimeframe == "1m" }
                .trendTimeframe
        )
        assertEquals(
            "1h",
            variants.first { it.primaryTimeframe == "30m" }
                .confirmationTimeframe
        )
        assertEquals(
            "4h",
            variants.first { it.primaryTimeframe == "30m" }
                .trendTimeframe
        )
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
    fun defaultCatalogContainsEveryCategory() {
        val configs = StrategyDefaults.all()

        assertEquals(8, configs.size)
        assertEquals(
            SignalCategory.values().toSet(),
            configs.map { it.category }.toSet()
        )
    }
}
