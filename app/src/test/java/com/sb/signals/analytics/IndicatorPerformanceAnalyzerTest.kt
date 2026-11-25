package com.sb.signals.analytics

import com.sb.signals.TestSignals
import com.sb.signals.domain.SignalLifecycleStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorPerformanceAnalyzerTest {

    @Test
    fun smallSamplesAreExplicitlyFlaggedAsNotReliable() {
        val sample = (1..5).map { id ->
            TestSignals.signal(
                id = id.toLong(),
                status = SignalLifecycleStatus.TARGET_3_HIT,
                outcomeNet = 1.0,
                outcomeR = 1.0
            )
        }
        val results = IndicatorPerformanceAnalyzer.analyze(sample)
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { !it.sampleAdequate })
    }

    @Test
    fun minimumThirtyConfirmedSignalsFlagsAdequateSample() {
        val sample = (1..IndicatorPerformanceAnalyzer.MIN_RELIABLE_SAMPLE_SIZE).map { id ->
            TestSignals.signal(
                id = id.toLong(),
                status = SignalLifecycleStatus.TARGET_3_HIT,
                outcomeNet = 1.0,
                outcomeR = 1.0
            )
        }
        val results = IndicatorPerformanceAnalyzer.analyze(sample)
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.sampleAdequate })
    }
}
