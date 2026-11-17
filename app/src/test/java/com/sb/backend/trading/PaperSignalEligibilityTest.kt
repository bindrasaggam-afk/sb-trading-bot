package com.sb.backend.trading

import com.sb.signals.TestSignals
import com.sb.signals.domain.SignalDirection
import com.sb.signals.domain.SignalLifecycleStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PaperSignalEligibilityTest {

    private val now = TestSignals.testNow
    private val maxAge = 60_000L

    @Test
    fun `new and active signals become pending`() {
        val newDecision = decide(
            TestSignals.signal(status = SignalLifecycleStatus.NEW)
        )
        assertEquals(PaperSignalForwardAction.CREATE_PENDING, newDecision.action)

        val activeDecision = decide(
            TestSignals.signal(status = SignalLifecycleStatus.ACTIVE)
        )
        assertEquals(
            PaperSignalForwardAction.CREATE_PENDING,
            activeDecision.action
        )
    }

    @Test
    fun `triggered signal fills from fresh in-range quote`() {
        val decision = decide(
            signal = TestSignals.signal(status = SignalLifecycleStatus.TRIGGERED),
            quote = PaperSignalQuote(price = 100.0, receivedAt = now - 1_000L)
        )

        assertEquals(PaperSignalForwardAction.FILL_NOW, decision.action)
        assertEquals(100.0, decision.quote?.price ?: 0.0, 0.000001)
    }

    @Test
    fun `triggered signal skips missing stale or out-of-range quotes`() {
        val triggered = TestSignals.signal(
            status = SignalLifecycleStatus.TRIGGERED
        )
        assertEquals(
            PaperSignalSkipReason.MISSING_QUOTE,
            decide(triggered, quote = null).reasonCode
        )
        assertEquals(
            PaperSignalSkipReason.STALE_QUOTE,
            decide(
                triggered,
                quote = PaperSignalQuote(100.0, now - maxAge - 1L)
            ).reasonCode
        )
        assertEquals(
            PaperSignalSkipReason.OUTSIDE_ENTRY_RANGE,
            decide(
                triggered,
                quote = PaperSignalQuote(102.0, now - 1_000L)
            ).reasonCode
        )
    }

    @Test
    fun `triggered sell signal fills from fresh in-range quote`() {
        val decision = decide(
            signal = TestSignals.signal(
                direction = SignalDirection.SELL,
                entryLow = 99.0,
                entryHigh = 101.0,
                reference = 100.0,
                stop = 102.0,
                target1 = 98.0,
                target2 = 96.0,
                target3 = 94.0,
                status = SignalLifecycleStatus.TRIGGERED
            ),
            quote = PaperSignalQuote(100.0, now - 1_000L)
        )

        assertEquals(PaperSignalForwardAction.FILL_NOW, decision.action)
    }

    @Test
    fun `partially completed signals are never replayed`() {
        listOf(
            SignalLifecycleStatus.TARGET_1_HIT,
            SignalLifecycleStatus.TARGET_2_HIT
        ).forEach { status ->
            assertEquals(
                PaperSignalSkipReason.PARTIAL_TARGET_ALREADY_HIT,
                decide(TestSignals.signal(status = status)).reasonCode
            )
        }
    }

    @Test
    fun `completed signal statuses are skipped`() {
        listOf(
            SignalLifecycleStatus.TARGET_3_HIT,
            SignalLifecycleStatus.STOPLOSS_HIT,
            SignalLifecycleStatus.EXPIRED,
            SignalLifecycleStatus.INVALIDATED,
            SignalLifecycleStatus.CLOSED
        ).forEach { status ->
            assertEquals(
                PaperSignalSkipReason.SIGNAL_COMPLETED,
                decide(TestSignals.signal(status = status)).reasonCode
            )
        }
    }

    @Test
    fun `expired and invalid signals are skipped`() {
        assertEquals(
            PaperSignalSkipReason.SIGNAL_EXPIRED,
            decide(
                TestSignals.signal(expiresAt = now)
            ).reasonCode
        )
        assertEquals(
            PaperSignalSkipReason.INVALID_SIGNAL,
            decide(
                TestSignals.signal().copy(target1 = 98.0)
            ).reasonCode
        )
    }

    private fun decide(
        signal: com.sb.signals.domain.ResearchSignal,
        quote: PaperSignalQuote? = null
    ): PaperSignalForwardDecision {
        return PaperSignalEligibility.decide(
            signal = signal,
            now = now,
            quote = quote,
            quoteMaxAgeMs = maxAge
        )
    }
}
