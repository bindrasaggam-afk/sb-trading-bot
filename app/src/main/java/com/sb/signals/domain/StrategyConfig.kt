package com.sb.signals.domain

data class StrategyConfig(
    val strategyId: String,
    val version: String,
    val category: SignalCategory,
    val name: String,
    val primaryTimeframe: String = when (category) {
        SignalCategory.INTRADAY -> "5m"
        else -> "1D"
    },
    val confirmationTimeframe: String = when (category) {
        SignalCategory.INTRADAY -> "15m"
        else -> "1W"
    },
    val trendTimeframe: String = when (category) {
        SignalCategory.INTRADAY -> "1h"
        SignalCategory.SWING -> "1W"
        SignalCategory.DELIVERY -> "1M"
    },
    val minScore: Double = 65.0,
    val minScoreLead: Double = 8.0,
    val minPrimaryCandles: Int = 200,
    val minConfirmationCandles: Int = 60,
    val minTrendCandles: Int = 50,
    val emaFast: Int = 20,
    val emaSlow: Int = 50,
    val emaTrend: Int = 200,
    val rsiPeriod: Int = 14,
    val atrPeriod: Int = 14,
    val adxPeriod: Int = 14,
    val smaPeriod: Int = 20,
    val supertrendPeriod: Int = 10,
    val supertrendMultiplier: Double = 3.0,
    val bollingerPeriod: Int = 20,
    val bollingerMultiplier: Double = 2.0,
    val volumePeriod: Int = 20,
    val volumeMultiplier: Double = 1.10,
    val minAdx: Double = 18.0,
    val stopAtrMultiplier: Double = 1.50,
    val entryAtrBuffer: Double = 0.20,
    val targetR1: Double = 1.5,
    val targetR2: Double = 3.0,
    val targetR3: Double = 4.5,
    val costBpsPerSide: Double = 5.0,
    val slippageBpsPerSide: Double = 2.0,
    val maxSignalAgeBars: Int = 3,
    val allowShorts: Boolean = true,
    val deliveryHoldingPeriod: HoldingPeriod = HoldingPeriod.FIVE_MONTHS
) {
    init {
        require(strategyId.isNotBlank())
        require(version.isNotBlank())
        require(primaryTimeframe.isNotBlank())
        require(confirmationTimeframe.isNotBlank())
        require(trendTimeframe.isNotBlank())
        require(minScore in 0.0..100.0)
        require(minScoreLead >= 0.0)
        require(minPrimaryCandles > 0)
        require(minConfirmationCandles > 0)
        require(minTrendCandles > 0)
        require(emaFast > 0 && emaSlow > emaFast && emaTrend > emaSlow)
        require(atrPeriod > 0 && adxPeriod > 1)
        require(smaPeriod > 0)
        require(supertrendPeriod > 1 && supertrendMultiplier > 0.0)
        require(bollingerPeriod > 1 && bollingerMultiplier > 0.0)
        require(volumePeriod > 0)
        require(stopAtrMultiplier.isFinite() && stopAtrMultiplier > 0.0)
        require(entryAtrBuffer.isFinite() && entryAtrBuffer > 0.0)
        require(targetR1.isFinite() && targetR2.isFinite() && targetR3.isFinite())
        require(targetR1 > 0.0 && targetR2 > targetR1 && targetR3 > targetR2)
        require(minAdx.isFinite() && minAdx in 0.0..100.0)
        require(volumeMultiplier.isFinite() && volumeMultiplier > 0.0)
        require(costBpsPerSide.isFinite() && slippageBpsPerSide.isFinite())
        require(costBpsPerSide >= 0.0 && slippageBpsPerSide >= 0.0)
        require(maxSignalAgeBars > 0)
    }

    val totalCostPercent: Double
        get() = ((costBpsPerSide + slippageBpsPerSide) * 2.0) / 100.0
}

object StrategyDefaults {

    // Only the validated 5m intraday strategy is active for observation.
    // Other timeframe variants remain intentionally disabled until separately validated.
    val intradayTimeframes: List<String> = listOf("5m")

    fun intraday(timeframe: String = "5m"): StrategyConfig {
        require(timeframe in intradayTimeframes)
        val (confirmation, trend) = intradayHigherTimeframes(timeframe)
        val suffix = if (timeframe == "5m") "" else "-$timeframe"
        return StrategyConfig(
            strategyId = "intraday-trend-breakout$suffix",
            version = "1.1.0",
            category = SignalCategory.INTRADAY,
            name = if (timeframe == "5m") {
                "Intraday trend and breakout"
            } else {
                "Intraday trend and breakout $timeframe"
            },
            primaryTimeframe = timeframe,
            confirmationTimeframe = confirmation,
            trendTimeframe = trend,
            minPrimaryCandles = 200,
            minConfirmationCandles = 40,
            minTrendCandles = 40,
            targetR1 = 1.5,
            targetR2 = 3.0,
            targetR3 = 4.5
        )
    }

    fun intradayVariants(): List<StrategyConfig> =
        intradayTimeframes.map { intraday(it) }

    fun swing(): StrategyConfig = StrategyConfig(
        strategyId = "swing-trend-pullback",
        version = "1.1.0",
        category = SignalCategory.SWING,
        name = "Swing trend continuation",
        minPrimaryCandles = 120,
        minConfirmationCandles = 30,
        minTrendCandles = 30,
        minAdx = 20.0,
        stopAtrMultiplier = 2.0,
        targetR1 = 2.0,
        targetR2 = 4.0,
        targetR3 = 6.0
    )

    fun delivery(): StrategyConfig = StrategyConfig(
        strategyId = "delivery-positional",
        version = "1.1.0",
        category = SignalCategory.DELIVERY,
        name = "Delivery positional trend",
        minPrimaryCandles = 200,
        minConfirmationCandles = 30,
        minTrendCandles = 30,
        minAdx = 18.0,
        stopAtrMultiplier = 2.5,
        targetR1 = 2.5,
        targetR2 = 5.0,
        targetR3 = 8.0,
        allowShorts = false,
        deliveryHoldingPeriod = HoldingPeriod.FIVE_MONTHS
    )

    fun all(): List<StrategyConfig> =
        intradayVariants() + swing() + delivery()

    fun forCategory(category: SignalCategory): StrategyConfig =
        when (category) {
            SignalCategory.INTRADAY -> intraday()
            SignalCategory.SWING -> swing()
            SignalCategory.DELIVERY -> delivery()
        }

    private fun intradayHigherTimeframes(
        primary: String
    ): Pair<String, String> = when (primary) {
        "1m" -> "5m" to "15m"
        "3m" -> "15m" to "1h"
        "5m" -> "15m" to "1h"
        "10m" -> "30m" to "1h"
        "15m" -> "1h" to "4h"
        "30m" -> "1h" to "4h"
        else -> "15m" to "1h"
    }
}