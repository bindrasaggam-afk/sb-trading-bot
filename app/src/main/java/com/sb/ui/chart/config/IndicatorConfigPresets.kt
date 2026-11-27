package com.sb.ui.chart.config

object IndicatorConfigPresets {

    fun all(): List<Pair<String, IndicatorConfig>> {
        val base = IndicatorConfigPolicy.DEFAULT
        return listOf(
            "Default" to base,
            "Scalp" to base.copy(
                source = IndicatorSource.HIGH,
                emaPeriod = 9,
                smaPeriod = 20,
                rsiPeriod = 7,
                macdFast = 8,
                macdSlow = 21,
                macdSignal = 5,
                bollingerPeriod = 25,
                bollingerMultiplier = 2.5,
                supertrendPeriod = 12,
                supertrendMultiplier = 4.0
            ),
            "Swing" to base.copy(
                source = IndicatorSource.LOW,
                emaPeriod = 21,
                smaPeriod = 100,
                rsiPeriod = 21,
                macdFast = 19,
                macdSlow = 39,
                macdSignal = 9,
                bollingerPeriod = 30,
                bollingerMultiplier = 2.0,
                supertrendPeriod = 20,
                supertrendMultiplier = 3.0
            ),
            "Typical" to base.copy(
                source = IndicatorSource.TYPICAL_PRICE,
                emaPeriod = 50,
                smaPeriod = 200,
                rsiPeriod = 6,
                macdFast = 12,
                macdSlow = 26,
                macdSignal = 9,
                bollingerPeriod = 20,
                bollingerMultiplier = 1.5,
                supertrendPeriod = 10,
                supertrendMultiplier = 2.0
            )
        )
    }
}
