package com.sb.signals.domain.strategies

import com.sb.signals.domain.HoldingPeriod
import com.sb.signals.domain.SignalCategory
import com.sb.signals.domain.SignalDirection
import com.sb.signals.domain.StrategyDefaults
import com.sb.signals.TestSignals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalRiskPlannerValidationTest {

    @Test
    fun buyPlanHasOrderedEntryStopAndTargets() {
        val config = StrategyDefaults.intraday()
        val plan = SignalRiskPlanner.plan(
            direction = SignalDirection.BUY,
            close = 100.0,
            atr = 2.0,
            category = SignalCategory.INTRADAY,
            holdingPeriod = HoldingPeriod.ONE_DAY,
            exchange = "NSE",
            now = TestSignals.testNow,
            config = config
        )
        assertNotNull(plan)
        val levels = plan!!.levels
        assertTrue(levels.entryLow <= levels.entryHigh)
        assertTrue(levels.stopLoss < levels.entryLow)
        assertTrue(levels.target1 > 100.0)
        assertTrue(levels.target2 > levels.target1)
        assertTrue(levels.target3 > levels.target2)
        assertTrue(plan.riskPerUnit > 0.0)
    }

    @Test
    fun sellPlanHasOrderedEntryStopAndTargets() {
        val config = StrategyDefaults.intraday()
        val plan = SignalRiskPlanner.plan(
            direction = SignalDirection.SELL,
            close = 100.0,
            atr = 2.0,
            category = SignalCategory.INTRADAY,
            holdingPeriod = HoldingPeriod.ONE_DAY,
            exchange = "NSE",
            now = TestSignals.testNow,
            config = config
        )
        assertNotNull(plan)
        val levels = plan!!.levels
        assertTrue(levels.entryLow <= levels.entryHigh)
        assertTrue(levels.stopLoss > levels.entryHigh)
        assertTrue(levels.target1 < 100.0)
        assertTrue(levels.target2 < levels.target1)
        assertTrue(levels.target3 < levels.target2)
        assertTrue(plan.riskPerUnit > 0.0)
    }

    @Test
    fun invalidPriceOrAtrRejected() {
        val config = StrategyDefaults.intraday()
        assertNull(
            SignalRiskPlanner.plan(
                direction = SignalDirection.BUY,
                close = Double.NaN,
                atr = 2.0,
                category = SignalCategory.INTRADAY,
                holdingPeriod = HoldingPeriod.ONE_DAY,
                exchange = "NSE",
                now = TestSignals.testNow,
                config = config
            )
        )
        assertNull(
            SignalRiskPlanner.plan(
                direction = SignalDirection.SELL,
                close = 100.0,
                atr = 0.0,
                category = SignalCategory.INTRADAY,
                holdingPeriod = HoldingPeriod.ONE_DAY,
                exchange = "NSE",
                now = TestSignals.testNow,
                config = config
            )
        )
    }
}
