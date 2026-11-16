package com.sb.signals.data

import com.sb.signals.domain.StrategyDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SignalJsonConfigTest {

    @Test
    fun strategyConfigRoundTripsWithIndicatorParameters() {
        val config = StrategyDefaults.intraday("1m").copy(
            smaPeriod = 30,
            supertrendPeriod = 12,
            supertrendMultiplier = 2.5,
            bollingerPeriod = 25,
            bollingerMultiplier = 2.2
        )

        val decoded = SignalJson.decodeConfig(
            SignalJson.encodeConfig(config)
        )

        assertEquals(config, decoded)
    }

    @Test
    fun legacyConfigUsesSafeDefaultsForNewParameters() {
        val decoded = SignalJson.decodeConfig(
            """
            {
              "strategyId": "swing-trend-pullback",
              "version": "1.1.0",
              "category": "SWING",
              "name": "Swing trend continuation",
              "primaryTimeframe": "1D",
              "confirmationTimeframe": "1W",
              "trendTimeframe": "1W"
            }
            """.trimIndent()
        )

        assertNotNull(decoded)
        assertEquals(20, decoded!!.smaPeriod)
        assertEquals(10, decoded.supertrendPeriod)
        assertEquals(3.0, decoded.supertrendMultiplier, 0.000001)
        assertEquals(20, decoded.bollingerPeriod)
        assertEquals(2.0, decoded.bollingerMultiplier, 0.000001)
    }

    @Test
    fun malformedConfigIsRejected() {
        assertNull(SignalJson.decodeConfig("{not-json"))
        assertNull(SignalJson.decodeConfig(""))
    }
}
