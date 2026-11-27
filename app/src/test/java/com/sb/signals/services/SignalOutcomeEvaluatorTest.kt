package com.sb.signals.services

import com.sb.backend.market.model.MarketCandle
import com.sb.signals.TestSignals
import com.sb.signals.domain.SignalDirection
import com.sb.signals.domain.SignalLifecycleStatus
import com.sb.signals.domain.StrategyDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalOutcomeEvaluatorTest {

    private val config = StrategyDefaults.intraday()

    @Test
    fun targetProgressionClosesAtThirdTarget() {
        val base = TestSignals.testNow
        val signal = TestSignals.signal(expiresAt = base + 86_400_000L)
        val future = listOf(
            candle(base, 100.0, 103.0, 99.0, 102.0),
            candle(base + 300_000L, 102.0, 106.5, 101.0, 105.0)
        )

        val result = SignalOutcomeEvaluator.evaluate(
            signal,
            future,
            now = base + 3_600_000L,
            config = config
        )!!

        assertEquals(SignalLifecycleStatus.TARGET_3_HIT, result.status)
        assertEquals(base, result.triggeredAt)
        assertEquals(base, result.target1At)
        assertEquals(base + 300_000L, result.target3At)
        assertEquals(106.0, result.exitPrice!!, 0.000001)
        assertEquals(3.0, result.outcomeR!!, 0.000001)
        assertTrue(result.outcomeNetPnlPercent!! < result.outcomePnlPercent!!)
    }

    @Test
    fun entryFillPriceDrivesOutcomeReturns() {
        val base = TestSignals.testNow
        val signal = TestSignals.signal(expiresAt = base + 86_400_000L)
        val future = listOf(
            candle(base, 99.5, 106.5, 99.2, 105.0)
        )

        val result = SignalOutcomeEvaluator.evaluate(
            signal,
            future,
            now = base + 3_600_000L,
            config = config
        )!!

        assertEquals(99.5, result.fillPrice!!, 0.000001)
        assertEquals(SignalLifecycleStatus.TARGET_3_HIT, result.status)
        assertEquals(6.5 / 1.5, result.outcomeR!!, 0.000001)
        assertEquals(6.5 / 99.5 * 100.0, result.outcomePnlPercent!!, 0.000001)
    }

    @Test
    fun entryFillPriceStaysInsideEntryZone() {
        val base = TestSignals.testNow
        val signal = TestSignals.signal(expiresAt = base + 86_400_000L)
        val future = listOf(
            candle(base, 98.5, 99.4, 98.5, 99.3)
        )

        val result = SignalOutcomeEvaluator.evaluate(
            signal,
            future,
            now = base + 3_600_000L,
            config = config
        )!!

        assertEquals(99.0, result.fillPrice!!, 0.000001)
        assertEquals(SignalLifecycleStatus.TRIGGERED, result.status)
    }

    @Test
    fun reevaluationUsesPersistedFillPrice() {
        val base = TestSignals.testNow
        val signal = TestSignals.signal(
            status = SignalLifecycleStatus.TRIGGERED,
            triggeredAt = base,
            fillPrice = 99.4,
            expiresAt = base + 86_400_000L
        )
        val future = listOf(
            candle(base, 100.0, 106.5, 99.5, 105.0)
        )

        val result = SignalOutcomeEvaluator.evaluate(
            signal,
            future,
            now = base + 3_600_000L,
            config = config
        )!!

        assertEquals(99.4, result.fillPrice!!, 0.000001)
        assertEquals(SignalLifecycleStatus.TARGET_3_HIT, result.status)
        assertEquals(6.6 / 1.4, result.outcomeR!!, 0.000001)
    }

    @Test
    fun sellEntryUsesFillPriceForReturns() {
        val base = TestSignals.testNow
        val signal = TestSignals.signal(
            direction = SignalDirection.SELL,
            reference = 100.0,
            stop = 102.0,
            target1 = 98.0,
            target2 = 96.0,
            target3 = 94.0,
            expiresAt = base + 86_400_000L
        )
        val future = listOf(
            candle(base, 100.5, 101.0, 93.5, 94.0)
        )

        val result = SignalOutcomeEvaluator.evaluate(
            signal,
            future,
            now = base + 3_600_000L,
            config = config
        )!!

        assertEquals(100.5, result.fillPrice!!, 0.000001)
        assertEquals(SignalLifecycleStatus.TARGET_3_HIT, result.status)
        assertEquals(6.5 / 1.5, result.outcomeR!!, 0.000001)
    }

    @Test
    fun stopClosesWithNegativeR() {
        val base = TestSignals.testNow
        val signal = TestSignals.signal(expiresAt = base + 86_400_000L)
        val future = listOf(
            candle(base, 100.0, 101.0, 99.0, 100.0),
            candle(base + 300_000L, 100.0, 100.5, 97.0, 97.5)
        )

        val result = SignalOutcomeEvaluator.evaluate(
            signal,
            future,
            now = base + 3_600_000L,
            config = config
        )!!

        assertEquals(SignalLifecycleStatus.STOPLOSS_HIT, result.status)
        assertEquals(98.0, result.exitPrice!!, 0.000001)
        assertEquals(-1.0, result.outcomeR!!, 0.000001)
        assertTrue(result.outcomeNetPnlPercent!! < 0.0)
    }

    @Test
    fun stopWinsWhenStopAndTargetShareBar() {
        val base = TestSignals.testNow
        val signal = TestSignals.signal(expiresAt = base + 86_400_000L)
        val future = listOf(
            candle(base, 100.0, 106.5, 97.0, 100.0)
        )

        val result = SignalOutcomeEvaluator.evaluate(
            signal,
            future,
            now = base + 3_600_000L,
            config = config
        )!!

        assertEquals(SignalLifecycleStatus.STOPLOSS_HIT, result.status)
        assertEquals(98.0, result.exitPrice!!, 0.000001)
        assertTrue(result.ambiguousBar)
        assertNull(result.target1At)
    }

    @Test
    fun stopBeforeEntryInvalidatesWithoutOutcome() {
        val base = TestSignals.testNow
        val signal = TestSignals.signal(expiresAt = base + 86_400_000L)
        val future = listOf(
            candle(base, 97.5, 98.5, 96.5, 97.0)
        )

        val result = SignalOutcomeEvaluator.evaluate(
            signal,
            future,
            now = base + 3_600_000L,
            config = config
        )!!

        assertEquals(SignalLifecycleStatus.INVALIDATED, result.status)
        assertEquals(base, result.closedAt)
        assertNull(result.triggeredAt)
        assertNull(result.fillPrice)
        assertNull(result.outcomeR)
        assertEquals(signal.stopLoss, result.eventPrice!!, 0.000001)
        assertEquals("Invalidated before entry: stop reached", result.reason)
    }

    @Test
    fun targetBeforeEntryInvalidatesAsMissedEntry() {
        val base = TestSignals.testNow
        val signal = TestSignals.signal(expiresAt = base + 86_400_000L)
        val future = listOf(
            candle(base, 102.5, 106.5, 102.2, 105.0)
        )

        val result = SignalOutcomeEvaluator.evaluate(
            signal,
            future,
            now = base + 3_600_000L,
            config = config
        )!!

        assertEquals(SignalLifecycleStatus.INVALIDATED, result.status)
        assertNull(result.triggeredAt)
        assertNull(result.fillPrice)
        assertEquals(signal.target3, result.eventPrice!!, 0.000001)
        assertEquals(
            "Invalidated before entry: target reached",
            result.reason
        )
    }

    @Test
    fun invalidatedStatusIsTerminal() {
        assertTrue(SignalLifecycleStatus.INVALIDATED.isCompleted)
        assertTrue(!SignalLifecycleStatus.INVALIDATED.isOpen)
    }

    @Test
    fun expiryWithoutEntryIsCompletedButHasNoOutcome() {
        val base = TestSignals.testNow
        val signal = TestSignals.signal(
            entryLow = 110.0,
            entryHigh = 112.0,
            reference = 111.0,
            stop = 108.0,
            target1 = 114.0,
            target2 = 117.0,
            target3 = 120.0,
            expiresAt = base + 1_800_000L
        )
        val future = listOf(
            candle(base, 109.0, 109.5, 108.5, 109.0)
        )

        val result = SignalOutcomeEvaluator.evaluate(
            signal,
            future,
            now = base + 7_200_000L,
            config = config
        )!!

        assertEquals(SignalLifecycleStatus.EXPIRED, result.status)
        assertNull(result.triggeredAt)
        assertNull(result.exitPrice)
        assertNull(result.outcomeR)
    }

    @Test
    fun gapThroughStopUsesOpenPrice() {
        val base = TestSignals.testNow
        val signal = TestSignals.signal(expiresAt = base + 86_400_000L)
        val future = listOf(
            candle(base, 100.0, 101.0, 99.0, 100.0),
            candle(base + 300_000L, 95.0, 96.0, 94.0, 94.5)
        )

        val result = SignalOutcomeEvaluator.evaluate(
            signal,
            future,
            now = base + 3_600_000L,
            config = config
        )!!

        assertEquals(SignalLifecycleStatus.STOPLOSS_HIT, result.status)
        assertEquals(95.0, result.exitPrice!!, 0.000001)
        assertEquals(-2.5, result.outcomeR!!, 0.000001)
    }

    @Test
    fun reevaluationDoesNotUseCandlesBeforeTrigger() {
        val base = TestSignals.testNow
        val signal = TestSignals.signal(
            status = SignalLifecycleStatus.TRIGGERED,
            triggeredAt = base + 300_000L,
            expiresAt = base + 86_400_000L
        )
        val future = listOf(
            candle(base, 100.0, 101.0, 90.0, 91.0),
            candle(base + 300_000L, 100.0, 101.0, 99.0, 100.0),
            candle(base + 600_000L, 100.0, 102.5, 99.5, 102.0)
        )

        val result = SignalOutcomeEvaluator.evaluate(
            signal,
            future,
            now = base + 3_600_000L,
            config = config
        )!!

        assertEquals(SignalLifecycleStatus.TARGET_1_HIT, result.status)
        assertNull(result.stoppedAt)
    }

    private fun candle(
        timestamp: Long,
        open: Double,
        high: Double,
        low: Double,
        close: Double
    ): MarketCandle {
        return MarketCandle(
            timestamp = timestamp,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = 1_000L
        )
    }
}
