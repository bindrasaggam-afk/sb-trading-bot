package com.sb.ui.chart.config

import com.sb.ui.chart.ChartIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IndicatorConfigCodecTest {

    @Test
    fun roundTripsCustomConfig() {
        val config = IndicatorConfig(
            enabled = setOf(ChartIndicator.EMA_20, ChartIndicator.SUPERTREND),
            timeframeKey = "5m",
            source = IndicatorSource.TYPICAL_PRICE,
            emaPeriod = 9,
            smaPeriod = 65,
            rsiPeriod = 7,
            macdFast = 8,
            macdSlow = 21,
            macdSignal = 5,
            bollingerPeriod = 25,
            bollingerMultiplier = 2.5,
            supertrendPeriod = 12,
            supertrendMultiplier = 4.0
        )

        val decoded = IndicatorConfigCodec.decode(IndicatorConfigCodec.encode(config))

        assertNotNull(decoded)
        assertEquals(config, decoded)
    }

    @Test
    fun encodingIsStableAfterNormalization() {
        val first = IndicatorConfig(
            enabled = setOf(ChartIndicator.MACD, ChartIndicator.EMA_20),
            timeframeKey = " 1m "
        )
        val second = first.copy(
            enabled = setOf(ChartIndicator.EMA_20, ChartIndicator.MACD),
            timeframeKey = "1m"
        )

        assertEquals(IndicatorConfigCodec.encode(first), IndicatorConfigCodec.encode(second))
    }

    @Test
    fun rejectsMalformedAndInvalidDocuments() {
        assertNull(IndicatorConfigCodec.decode("{"))
        assertNull(IndicatorConfigCodec.decode("""{"version":99}"""))
        assertNull(
            IndicatorConfigCodec.decode(
                IndicatorConfigCodec.encode(IndicatorConfigPolicy.DEFAULT.copy(emaPeriod = 1))
            )
        )
    }
}
