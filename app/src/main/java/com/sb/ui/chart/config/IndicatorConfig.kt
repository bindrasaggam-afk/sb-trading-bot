package com.sb.ui.chart.config

import com.sb.ui.chart.ChartCandle
import com.sb.ui.chart.ChartIndicator

enum class IndicatorSource(val label: String) {
    CLOSE("Close"),
    OPEN("Open"),
    HIGH("High"),
    LOW("Low"),
    TYPICAL_PRICE("Typical price");

    fun value(candle: ChartCandle): Double = when (this) {
        CLOSE -> candle.close
        OPEN -> candle.open
        HIGH -> candle.high
        LOW -> candle.low
        TYPICAL_PRICE -> (candle.high + candle.low + candle.close) / 3.0
    }

    fun values(candles: List<ChartCandle>): List<Double> = candles.map { value(it) }
}

data class IndicatorConfig(
    val enabled: Set<ChartIndicator> = emptySet(),
    val timeframeKey: String = "",
    val source: IndicatorSource = IndicatorSource.CLOSE,
    val emaPeriod: Int = 20,
    val smaPeriod: Int = 50,
    val rsiPeriod: Int = 14,
    val macdFast: Int = 12,
    val macdSlow: Int = 26,
    val macdSignal: Int = 9,
    val bollingerPeriod: Int = 20,
    val bollingerMultiplier: Double = 2.0,
    val supertrendPeriod: Int = 10,
    val supertrendMultiplier: Double = 3.0
)

data class IndicatorConfigValidation(
    val ok: Boolean,
    val reason: String = ""
)

object IndicatorConfigPolicy {

    const val FORMAT_VERSION = 1
    private const val MIN_PERIOD = 2
    private const val MAX_PERIOD = 500
    private const val MAX_MULTIPLIER = 10.0

    val DEFAULT = IndicatorConfig()

    fun validate(config: IndicatorConfig): IndicatorConfigValidation {
        if (config.emaPeriod !in MIN_PERIOD..MAX_PERIOD) return invalid("invalid-ema-period")
        if (config.smaPeriod !in MIN_PERIOD..MAX_PERIOD) return invalid("invalid-sma-period")
        if (config.rsiPeriod !in MIN_PERIOD..MAX_PERIOD) return invalid("invalid-rsi-period")
        if (config.macdFast !in MIN_PERIOD..MAX_PERIOD) return invalid("invalid-macd-fast")
        if (config.macdSlow !in MIN_PERIOD..MAX_PERIOD) return invalid("invalid-macd-slow")
        if (config.macdSignal !in MIN_PERIOD..MAX_PERIOD) return invalid("invalid-macd-signal")
        if (config.macdFast >= config.macdSlow) return invalid("macd-fast-not-below-slow")
        if (config.bollingerPeriod !in MIN_PERIOD..MAX_PERIOD) {
            return invalid("invalid-bollinger-period")
        }
        if (!validMultiplier(config.bollingerMultiplier)) {
            return invalid("invalid-bollinger-multiplier")
        }
        if (config.supertrendPeriod !in MIN_PERIOD..MAX_PERIOD) {
            return invalid("invalid-supertrend-period")
        }
        if (!validMultiplier(config.supertrendMultiplier)) {
            return invalid("invalid-supertrend-multiplier")
        }
        return IndicatorConfigValidation(true)
    }

    fun normalize(config: IndicatorConfig): IndicatorConfig = config.copy(
        enabled = config.enabled.toSortedSet(compareBy { it.name }),
        timeframeKey = config.timeframeKey.trim()
    )

    fun fromLegacy(
        enabled: Set<ChartIndicator>,
        timeframeKey: String = ""
    ): IndicatorConfig = DEFAULT.copy(
        enabled = enabled,
        timeframeKey = timeframeKey
    )

    fun withEnabled(
        config: IndicatorConfig,
        indicator: ChartIndicator,
        enabled: Boolean
    ): IndicatorConfig {
        val next = if (enabled) {
            config.enabled + indicator
        } else {
            config.enabled - indicator
        }
        return config.copy(enabled = next)
    }

    fun toggle(
        config: IndicatorConfig,
        indicator: ChartIndicator
    ): IndicatorConfig = withEnabled(config, indicator, indicator !in config.enabled)

    fun label(config: IndicatorConfig, indicator: ChartIndicator): String {
        val suffix = if (config.source == IndicatorSource.CLOSE) {
            ""
        } else {
            " ${config.source.label}"
        }
        return when (indicator) {
            ChartIndicator.EMA_20 -> "EMA ${config.emaPeriod}$suffix"
            ChartIndicator.SMA_50 -> "SMA ${config.smaPeriod}$suffix"
            ChartIndicator.VWAP -> "VWAP"
            ChartIndicator.BOLLINGER ->
                "BB ${config.bollingerPeriod}×${format(config.bollingerMultiplier)}$suffix"
            ChartIndicator.SUPERTREND ->
                "Supertrend ${config.supertrendPeriod}×${format(config.supertrendMultiplier)}"
            ChartIndicator.RSI_14 -> "RSI ${config.rsiPeriod}$suffix"
            ChartIndicator.MACD ->
                "MACD ${config.macdFast}/${config.macdSlow}/${config.macdSignal}$suffix"
        }
    }

    private fun validMultiplier(value: Double): Boolean =
        value.isFinite() && value > 0.0 && value <= MAX_MULTIPLIER

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private fun invalid(reason: String) = IndicatorConfigValidation(false, reason)
}
