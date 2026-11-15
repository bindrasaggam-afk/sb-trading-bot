package com.sb.signals.domain

data class StrategyConfig(
    val strategyId: String,
    val version: String,
    val category: SignalCategory,
    val name: String,
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
    val deliveryHoldingPeriod: HoldingPeriod = HoldingPeriod.SIX_MONTHS
) {
    init {
        require(strategyId.isNotBlank())
        require(version.isNotBlank())
        require(minScore in 0.0..100.0)
        require(minScoreLead >= 0.0)
        require(minPrimaryCandles > 0)
        require(minConfirmationCandles > 0)
        require(minTrendCandles > 0)
        require(emaFast > 0 && emaSlow > emaFast && emaTrend > emaSlow)
        require(atrPeriod > 0 && adxPeriod > 1)
        require(volumePeriod > 0)
        require(stopAtrMultiplier > 0.0)
        require(targetR1 > 0.0 && targetR2 > targetR1 && targetR3 > targetR2)
        require(costBpsPerSide >= 0.0 && slippageBpsPerSide >= 0.0)
    }

    val totalCostPercent: Double
        get() = ((costBpsPerSide + slippageBpsPerSide) * 2.0) / 100.0
}

object StrategyDefaults {
    fun intraday(): StrategyConfig = StrategyConfig(
        strategyId = "intraday-trend-breakout",
        version = "1.0.0",
        category = SignalCategory.INTRADAY,
        name = "Intraday trend and breakout",
        minPrimaryCandles = 200,
        minConfirmationCandles = 40,
        minTrendCandles = 40,
        targetR1 = 1.5,
        targetR2 = 3.0,
        targetR3 = 4.5
    )

    fun swing(): StrategyConfig = StrategyConfig(
        strategyId = "swing-trend-pullback",
        version = "1.0.0",
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
        version = "1.0.0",
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
        deliveryHoldingPeriod = HoldingPeriod.SIX_MONTHS
    )

    fun forCategory(category: SignalCategory): StrategyConfig =
        when (category) {
            SignalCategory.INTRADAY -> intraday()
            SignalCategory.SWING -> swing()
            SignalCategory.DELIVERY -> delivery()
        }
}
