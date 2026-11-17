package com.sb.researchanalytics.domain

import com.sb.signals.data.SignalJson
import com.sb.signals.domain.ResearchSignal
import com.sb.signals.domain.StrategyConfig
import com.sb.signals.domain.StrategyDefaults

/**
 * Builds the analytics snapshot strictly from data already attached to a
 * research signal. No candles are fetched and no indicator is recalculated.
 */
object ResearchSnapshotFactory {

    fun fromSignal(
        signal: ResearchSignal,
        capturedAt: Long = System.currentTimeMillis()
    ): SnapshotPair = build(
        signal = signal,
        values = signal.indicatorSnapshot.values,
        confirmations = signal.indicatorSnapshot.confirmations,
        scores = signal.indicatorSnapshot.scores,
        parameters = signal.indicatorSnapshot.parameters,
        source = signal.canonicalSnapshotSource(),
        capturedAt = capturedAt
    )

    fun fromStored(
        signal: ResearchSignal,
        values: Map<String, Double?>,
        confirmations: Map<String, Boolean>,
        scores: Map<String, Double?>,
        parameters: Map<String, Double?>,
        source: String,
        capturedAt: Long
    ): SnapshotPair = build(
        signal = signal,
        values = values,
        confirmations = confirmations,
        scores = scores,
        parameters = parameters,
        source = source,
        capturedAt = capturedAt
    )

    private fun build(
        signal: ResearchSignal,
        values: Map<String, Double?>,
        confirmations: Map<String, Boolean>,
        scores: Map<String, Double?>,
        parameters: Map<String, Double?>,
        source: String,
        capturedAt: Long
    ): SnapshotPair {
        val config = SignalJson.decodeConfig(signal.strategyConfigJson)
            ?: StrategyDefaults.forCategory(signal.category)

        val atr = value(
            values,
            "ATR${config.atrPeriod}",
            parameters["ATR Period"]?.toInt()
        )
        val close = value(values, "Close")
        val volume = value(values, "Volume")
        val volumeMa = value(
            values,
            "VolumeMA${config.volumePeriod}",
            parameters["Volume Period"]?.toInt()
        )
        val volumeRatio = if (volume != null &&
            volumeMa != null &&
            volumeMa > 0.0
        ) {
            volume / volumeMa
        } else {
            null
        }
        val volatilityPct = if (atr != null &&
            close != null &&
            close > 0.0
        ) {
            atr / close * 100.0
        } else {
            null
        }
        val adx = value(
            values,
            "ADX${config.adxPeriod}",
            parameters["ADX Period"]?.toInt()
        )

        val indicator = IndicatorSnapshotRecord(
            signalId = signal.id,
            snapshotVersion = IndicatorSnapshotRecord.SNAPSHOT_VERSION,
            source = source,
            capturedAt = capturedAt,
            candleTimestamp = signal.candleTimestamp,
            primaryTimeframe = config.primaryTimeframe,
            confirmationTimeframe = config.confirmationTimeframe,
            trendTimeframe = config.trendTimeframe,
            strategyConfigJson = signal.strategyConfigJson,
            values = values,
            scores = scores,
            confirmations = confirmations,
            parameters = parameters,
            emaFast = value(
                values,
                "EMA${config.emaFast}",
                parameters["EMA Fast"]?.toInt()
            ),
            emaSlow = value(
                values,
                "EMA${config.emaSlow}",
                parameters["EMA Slow"]?.toInt()
            ),
            emaTrend = value(
                values,
                "EMA${config.emaTrend}",
                parameters["EMA Trend"]?.toInt()
            ),
            rsi = value(
                values,
                "RSI${config.rsiPeriod}",
                parameters["RSI Period"]?.toInt()
            ),
            macd = value(values, "MACD"),
            macdSignal = value(values, "MACDSignal"),
            macdHistogram = value(values, "MACDHistogram"),
            supertrend = value(values, "Supertrend"),
            supertrendDirection = value(values, "SupertrendDirection"),
            adx = adx,
            plusDi = value(values, "PlusDI"),
            minusDi = value(values, "MinusDI"),
            vwap = value(values, "VWAP"),
            bbUpper = value(values, "BBUpper"),
            bbMiddle = value(values, "BBMiddle"),
            bbLower = value(values, "BBLower"),
            atr = atr,
            volume = volume,
            volumeMa = volumeMa,
            volumeRatio = volumeRatio,
            breakoutConfirmed = confirmations["Breakout"],
            higherTimeframeConfirmed =
                confirmations["Higher timeframe"],
            trendTimeframeConfirmed = confirmations["Trend timeframe"],
            totalScore = signal.confidence,
            bullScore = value(values, "BullScore"),
            bearScore = value(values, "BearScore")
        )

        val conditions = ArrayList<String>()
        if (adx != null) {
            conditions.add(adxBucket(adx))
        }
        if (volatilityPct != null) {
            conditions.add(volatilityBucket(volatilityPct))
        }
        if (volumeRatio != null) {
            conditions.add(volumeBucket(volumeRatio))
        }
        val market = MarketSnapshotRecord(
            signalId = signal.id,
            capturedAt = capturedAt,
            candleTimestamp = signal.candleTimestamp,
            volatilityPct = volatilityPct,
            volatilityBucket = volatilityPct?.let {
                volatilityBucket(it)
            } ?: MarketSnapshotRecord.UNAVAILABLE,
            adx = adx,
            adxBucket = adx?.let { adxBucket(it) }
                ?: MarketSnapshotRecord.UNAVAILABLE,
            volumeRatio = volumeRatio,
            volumeBucket = volumeRatio?.let {
                volumeBucket(it)
            } ?: MarketSnapshotRecord.UNAVAILABLE,
            higherTimeframeAligned = confirmations["Higher timeframe"],
            trendTimeframeAligned = confirmations["Trend timeframe"],
            sessionLabel = MarketSnapshotRecord.UNAVAILABLE,
            conditions = conditions
        )
        return SnapshotPair(indicator, market)
    }

    fun adxBucket(adx: Double): String = when {
        adx >= 25.0 -> MarketSnapshotRecord.TRENDING
        adx >= 18.0 -> MarketSnapshotRecord.WEAK_TREND
        else -> MarketSnapshotRecord.SIDEWAYS
    }

    fun volatilityBucket(volatilityPct: Double): String = when {
        volatilityPct >= 2.0 ->
            MarketSnapshotRecord.HIGH_VOLATILITY
        volatilityPct >= 1.0 ->
            MarketSnapshotRecord.MEDIUM_VOLATILITY
        else -> MarketSnapshotRecord.LOW_VOLATILITY
    }

    fun volumeBucket(ratio: Double): String = when {
        ratio >= 1.5 -> MarketSnapshotRecord.HIGH_VOLUME
        ratio >= 1.1 -> MarketSnapshotRecord.NORMAL_VOLUME
        else -> MarketSnapshotRecord.LOW_VOLUME
    }

    private fun value(
        values: Map<String, Double?>,
        key: String,
        period: Int? = null
    ): Double? {
        values[key]?.let { return it }
        if (period != null) {
            val generated = values["${key.substringBefore(period.toString())}$period"]
            if (generated != null) return generated
        }
        return null
    }
}
