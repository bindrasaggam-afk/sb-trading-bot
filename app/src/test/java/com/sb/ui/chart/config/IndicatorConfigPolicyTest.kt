package com.sb.ui.chart.config

import com.sb.ui.chart.ChartIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorConfigPolicyTest {

    @Test
    fun validatesRangesAndMacdOrdering() {
        assertTrue(valid(IndicatorConfigPolicy.DEFAULT))
        assertFalse(valid(IndicatorConfigPolicy.DEFAULT.copy(emaPeriod = 1)))
        assertFalse(valid(IndicatorConfigPolicy.DEFAULT.copy(macdFast = 26, macdSlow = 12)))
        assertFalse(valid(IndicatorConfigPolicy.DEFAULT.copy(bollingerMultiplier = 0.0)))
        assertFalse(valid(IndicatorConfigPolicy.DEFAULT.copy(supertrendMultiplier = Double.NaN)))
    }

    @Test
    fun normalizeSortsEnabledIndicators() {
        val normalized = IndicatorConfigPolicy.normalize(
            IndicatorConfig(
                enabled = setOf(ChartIndicator.MACD, ChartIndicator.EMA_20),
                timeframeKey = " 5m "
            )
        )

        assertEquals(
            listOf(ChartIndicator.EMA_20, ChartIndicator.MACD),
            normalized.enabled.toList()
        )
        assertEquals("5m", normalized.timeframeKey)
    }

    @Test
    fun fromLegacyToggleAndClearPreserveDefaults() {
        val legacy = IndicatorConfigPolicy.fromLegacy(
            setOf(ChartIndicator.RSI_14),
            "15m"
        )

        assertEquals(20, legacy.emaPeriod)
        assertEquals(IndicatorSource.CLOSE, legacy.source)
        assertEquals("15m", legacy.timeframeKey)
        assertEquals(emptySet<ChartIndicator>(), IndicatorConfigPolicy.toggle(legacy, ChartIndicator.RSI_14).enabled)
        assertEquals(
            setOf(ChartIndicator.RSI_14, ChartIndicator.MACD),
            IndicatorConfigPolicy.withEnabled(legacy, ChartIndicator.MACD, true).enabled
        )
    }

    @Test
    fun labelsUseConfiguredPeriodsAndSource() {
        val config = IndicatorConfigPolicy.DEFAULT.copy(
            source = IndicatorSource.HIGH,
            emaPeriod = 9,
            rsiPeriod = 6
        )

        assertEquals("EMA 9 High", IndicatorConfigPolicy.label(config, ChartIndicator.EMA_20))
        assertEquals("RSI 6 High", IndicatorConfigPolicy.label(config, ChartIndicator.RSI_14))
        assertEquals("VWAP", IndicatorConfigPolicy.label(config, ChartIndicator.VWAP))
    }

    private fun valid(config: IndicatorConfig): Boolean = IndicatorConfigPolicy.validate(config).ok
}
