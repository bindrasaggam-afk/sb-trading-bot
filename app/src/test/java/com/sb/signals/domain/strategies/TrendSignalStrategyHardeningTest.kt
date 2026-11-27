package com.sb.signals.domain.strategies

import com.sb.signals.TestSignals
import com.sb.signals.domain.StrategyDefaults
import org.junit.Assert.assertNull
import org.junit.Test

class TrendSignalStrategyHardeningTest {

    @Test
    fun intradayRejectsConflictingTrendTimeframe() {
        val config = StrategyDefaults.intraday()
        val primary = TestSignals.candles(
            count = 320,
            start = TestSignals.testNow - 320 * 300_000L,
            intervalMillis = 300_000L,
            startPrice = 100.0,
            step = 0.25
        )
        val confirmation = TestSignals.candles(
            count = 120,
            start = TestSignals.testNow - 120 * 900_000L,
            intervalMillis = 900_000L,
            startPrice = 100.0,
            step = 0.30
        )
        val trend = TestSignals.candles(
            count = 80,
            start = TestSignals.testNow - 80 * 3_600_000L,
            intervalMillis = 3_600_000L,
            startPrice = 120.0,
            step = -0.20
        )
        val candidate = TrendSignalStrategy(config).evaluate(
            StrategyInput(
                symbol = "RELIANCE",
                exchange = "NSE",
                now = TestSignals.testNow,
                primary = TestSignals.prepared(primary),
                confirmation = TestSignals.prepared(confirmation),
                trend = TestSignals.prepared(trend),
                config = config
            )
        )
        assertNull(candidate)
    }
}
