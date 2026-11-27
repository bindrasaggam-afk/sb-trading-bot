package com.sb.researchanalytics.domain

import com.sb.signals.domain.ResearchSignal

/**
 * Read-only projection of the exact indicator values captured when a research
 * signal was generated. Values are copied, never recomputed from live data.
 */
data class IndicatorSnapshotRecord(
    val signalId: Long,
    val snapshotVersion: String = "1.0",
    val source: String = SOURCE_CAPTURED,
    val capturedAt: Long,
    val candleTimestamp: Long,
    val primaryTimeframe: String,
    val confirmationTimeframe: String,
    val trendTimeframe: String,
    val strategyConfigJson: String,
    val values: Map<String, Double?>,
    val scores: Map<String, Double?>,
    val confirmations: Map<String, Boolean>,
    val parameters: Map<String, Double?>,
    val emaFast: Double? = null,
    val emaSlow: Double? = null,
    val emaTrend: Double? = null,
    val rsi: Double? = null,
    val macd: Double? = null,
    val macdSignal: Double? = null,
    val macdHistogram: Double? = null,
    val supertrend: Double? = null,
    val supertrendDirection: Double? = null,
    val adx: Double? = null,
    val plusDi: Double? = null,
    val minusDi: Double? = null,
    val vwap: Double? = null,
    val bbUpper: Double? = null,
    val bbMiddle: Double? = null,
    val bbLower: Double? = null,
    val atr: Double? = null,
    val volume: Double? = null,
    val volumeMa: Double? = null,
    val volumeRatio: Double? = null,
    val breakoutConfirmed: Boolean? = null,
    val higherTimeframeConfirmed: Boolean? = null,
    val trendTimeframeConfirmed: Boolean? = null,
    val totalScore: Double? = null,
    val bullScore: Double? = null,
    val bearScore: Double? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SOURCE_CAPTURED = "CAPTURED"
        const val SOURCE_RECONSTRUCTED = "RECONSTRUCTED_FROM_SIGNAL"
        const val SOURCE_NOT_AVAILABLE = "NOT_AVAILABLE"
        const val SNAPSHOT_VERSION = "1.0"
    }
}

data class MarketSnapshotRecord(
    val signalId: Long,
    val capturedAt: Long,
    val candleTimestamp: Long,
    val volatilityPct: Double? = null,
    val volatilityBucket: String = UNAVAILABLE,
    val adx: Double? = null,
    val adxBucket: String = UNAVAILABLE,
    val volumeRatio: Double? = null,
    val volumeBucket: String = UNAVAILABLE,
    val higherTimeframeAligned: Boolean? = null,
    val trendTimeframeAligned: Boolean? = null,
    val sessionLabel: String = UNAVAILABLE,
    val conditions: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val UNAVAILABLE = "Unavailable"
        const val TRENDING = "Trending (ADX >= 25)"
        const val WEAK_TREND = "Weak trend (ADX 18-25)"
        const val SIDEWAYS = "Sideways (ADX < 18)"
        const val HIGH_VOLATILITY = "High volatility"
        const val MEDIUM_VOLATILITY = "Medium volatility"
        const val LOW_VOLATILITY = "Low volatility"
        const val HIGH_VOLUME = "High volume"
        const val NORMAL_VOLUME = "Normal volume"
        const val LOW_VOLUME = "Low volume"
    }
}

data class SnapshotPair(
    val indicator: IndicatorSnapshotRecord,
    val market: MarketSnapshotRecord
)

internal fun ResearchSignal.hasRecordedSnapshot(): Boolean =
    indicatorSnapshot.values.isNotEmpty() ||
        indicatorSnapshot.confirmations.isNotEmpty()

internal fun ResearchSignal.canonicalSnapshotSource(): String =
    if (hasRecordedSnapshot()) {
        IndicatorSnapshotRecord.SOURCE_RECONSTRUCTED
    } else {
        IndicatorSnapshotRecord.SOURCE_NOT_AVAILABLE
    }
