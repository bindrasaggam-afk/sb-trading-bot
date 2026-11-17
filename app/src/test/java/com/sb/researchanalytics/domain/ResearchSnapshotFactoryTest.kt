package com.sb.researchanalytics.domain

import com.sb.signals.TestSignals
import com.sb.signals.domain.SignalIndicatorSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchSnapshotFactoryTest {

    @Test
    fun capturesExactStoredScoresWithoutChangingSignal() {
        val signal = TestSignals.signal(id = 10L).copy(
            indicatorSnapshot = SignalIndicatorSnapshot(
                values = linkedMapOf(
                    "Close" to 100.0,
                    "EMA20" to 99.5,
                    "EMA50" to 98.0,
                    "EMA200" to 95.0,
                    "RSI14" to 61.0,
                    "MACD" to 1.2,
                    "MACDSignal" to 0.8,
                    "MACDHistogram" to 0.4,
                    "Supertrend" to 97.0,
                    "SupertrendDirection" to 1.0,
                    "ADX14" to 31.0,
                    "PlusDI" to 28.0,
                    "MinusDI" to 14.0,
                    "VWAP" to 99.0,
                    "BBUpper" to 103.0,
                    "BBMiddle" to 100.0,
                    "BBLower" to 97.0,
                    "ATR14" to 1.0,
                    "Volume" to 2_000.0,
                    "VolumeMA20" to 1_000.0,
                    "BullScore" to 82.0,
                    "BearScore" to 12.0
                ),
                scores = linkedMapOf(
                    "EMA trend" to 18.0,
                    "RSI" to 10.0,
                    "ADX" to 10.0
                ),
                confirmations = linkedMapOf(
                    "EMA trend" to true,
                    "RSI" to true,
                    "ADX" to true,
                    "Higher timeframe" to true
                )
            )
        )
        val beforeValues = signal.indicatorSnapshot.values.toMap()
        val beforeScores = signal.indicatorSnapshot.scores.toMap()

        val pair = ResearchSnapshotFactory.fromSignal(
            signal,
            capturedAt = 123_456L
        )

        assertEquals(
            IndicatorSnapshotRecord.SOURCE_RECONSTRUCTED,
            pair.indicator.source
        )
        assertEquals(10L, pair.indicator.signalId)
        assertEquals(123_456L, pair.indicator.capturedAt)
        assertEquals(99.5, pair.indicator.emaFast ?: 0.0, 0.0)
        assertEquals(61.0, pair.indicator.rsi ?: 0.0, 0.0)
        assertEquals(2.0, pair.indicator.volumeRatio ?: 0.0, 0.0)
        assertEquals(1.0, pair.market.volatilityPct ?: 0.0, 0.0)
        assertEquals(18.0, pair.indicator.scores["EMA trend"] ?: 0.0, 0.0)
        assertEquals(
            MarketSnapshotRecord.TRENDING,
            pair.market.adxBucket
        )
        assertEquals(
            MarketSnapshotRecord.HIGH_VOLUME,
            pair.market.volumeBucket
        )
        assertEquals(beforeValues, signal.indicatorSnapshot.values)
        assertEquals(beforeScores, signal.indicatorSnapshot.scores)
    }

    @Test
    fun legacySignalIsMarkedUnavailableInsteadOfFabricatingValues() {
        val signal = TestSignals.signal(id = 11L).copy(
            indicatorSnapshot = SignalIndicatorSnapshot()
        )

        val pair = ResearchSnapshotFactory.fromSignal(signal)

        assertEquals(
            IndicatorSnapshotRecord.SOURCE_NOT_AVAILABLE,
            pair.indicator.source
        )
        assertTrue(pair.indicator.values.isEmpty())
        assertTrue(pair.indicator.scores.isEmpty())
        assertNull(pair.indicator.rsi)
        assertNull(pair.market.adxBucket.takeIf {
            it != MarketSnapshotRecord.UNAVAILABLE
        })
    }

    @Test
    fun storedSnapshotUsesRecordedSourceAndValues() {
        val signal = TestSignals.signal(id = 12L)
        val pair = ResearchSnapshotFactory.fromStored(
            signal = signal,
            values = mapOf("Close" to 100.0, "ADX14" to 19.0),
            confirmations = mapOf("EMA trend" to false),
            scores = mapOf("EMA trend" to 0.0),
            parameters = mapOf("ADX Period" to 14.0),
            source = IndicatorSnapshotRecord.SOURCE_RECONSTRUCTED,
            capturedAt = 999L
        )

        assertEquals(
            IndicatorSnapshotRecord.SOURCE_RECONSTRUCTED,
            pair.indicator.source
        )
        assertEquals(999L, pair.indicator.capturedAt)
        assertEquals(
            MarketSnapshotRecord.WEAK_TREND,
            pair.market.adxBucket
        )
        assertEquals(0.0, pair.indicator.scores["EMA trend"] ?: -1.0, 0.0)
    }
}
