package com.sb.signals.analytics

import com.sb.signals.TestSignals
import com.sb.signals.domain.SignalDirection
import com.sb.signals.domain.SignalLifecycleStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceAnalyzerTest {

    @Test
    fun computesAccuracyCoverageAndTerminalInvalidations() {
        val win = TestSignals.signal(
            id = 1L,
            status = SignalLifecycleStatus.TARGET_3_HIT,
            outcomeNet = 2.0,
            outcomeR = 2.0
        ).copy(confidence = 85.0)
        val loss = TestSignals.signal(
            id = 2L,
            direction = SignalDirection.SELL,
            status = SignalLifecycleStatus.STOPLOSS_HIT,
            outcomeNet = -1.0,
            outcomeR = -1.0
        ).copy(confidence = 65.0)
        val invalidated = TestSignals.signal(
            id = 3L,
            status = SignalLifecycleStatus.INVALIDATED
        )
        val open = TestSignals.signal(
            id = 4L,
            status = SignalLifecycleStatus.NEW
        )

        val stats = PerformanceAnalyzer.analyze(
            listOf(win, loss, invalidated, open)
        )

        assertEquals(4, stats.generated)
        assertEquals(3, stats.completed)
        assertEquals(2, stats.evaluated)
        assertEquals(1, stats.open)
        assertEquals(1, stats.invalidated)
        assertEquals(1, stats.wins)
        assertEquals(1, stats.losses)
        assertEquals(50.0, stats.winRatePct, 0.000001)
        assertEquals(50.0, stats.directionalAccuracyPct, 0.000001)
        assertEquals(50.0, stats.coveragePct, 0.000001)
        assertEquals(75.0, stats.completionRatePct, 0.000001)
        assertEquals(1, stats.byConfidence["Confidence 80-89"]?.total)
        assertEquals(1, stats.byConfidence["Confidence 60-69"]?.total)
        assertEquals(2, stats.bySymbol["ALL"]?.total)
    }

    @Test
    fun separatesTriggeredSignalsFromUntriggeredExpiry() {
        val triggered = TestSignals.signal(
            id = 1L,
            status = SignalLifecycleStatus.TARGET_3_HIT,
            triggeredAt = TestSignals.testNow,
            closedAt = TestSignals.testNow + 60_000L,
            outcomeNet = 2.0,
            outcomeR = 2.0
        )
        val expired = TestSignals.signal(
            id = 2L,
            status = SignalLifecycleStatus.EXPIRED,
            closedAt = TestSignals.testNow + 60_000L
        )
        val invalidated = TestSignals.signal(
            id = 3L,
            status = SignalLifecycleStatus.INVALIDATED,
            closedAt = TestSignals.testNow + 60_000L
        )
        val open = TestSignals.signal(
            id = 4L,
            status = SignalLifecycleStatus.NEW
        )

        val stats = PerformanceAnalyzer.analyze(
            listOf(triggered, expired, invalidated, open)
        )

        assertEquals(1, stats.triggered)
        assertEquals(1, stats.untriggeredExpired)
        assertEquals(1, stats.invalidated)
        assertEquals(25.0, stats.triggerRatePct, 0.000001)
        assertEquals(1, stats.evaluated)
    }
}
