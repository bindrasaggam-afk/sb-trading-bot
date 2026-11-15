package com.sb.signals.analytics

import com.sb.signals.domain.ResearchSignal

data class IndicatorPerformance(
    val indicator: String,
    val confirmedSignals: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val avgNetPnlPct: Double,
    val avgR: Double
)

object IndicatorPerformanceAnalyzer {

    fun analyze(signals: List<ResearchSignal>): List<IndicatorPerformance> {
        val traded = signals.filter {
            it.status.isCompleted && it.outcomeNetPnlPercent != null
        }
        val names = linkedSetOf<String>()
        traded.forEach { signal ->
            names.addAll(signal.indicatorSnapshot.confirmations.keys)
        }

        return names.map { name ->
            val confirmed = traded.filter {
                it.indicatorSnapshot.confirmations[name] == true
            }
            val values = confirmed.mapNotNull { it.outcomeNetPnlPercent }
            val rValues = confirmed.mapNotNull { it.outcomeR }
            val wins = values.count { it > 0.001 }
            val losses = values.count { it < -0.001 }
            IndicatorPerformance(
                indicator = name,
                confirmedSignals = values.size,
                wins = wins,
                losses = losses,
                winRatePct = if (values.isNotEmpty()) {
                    wins.toDouble() / values.size * 100.0
                } else {
                    0.0
                },
                avgNetPnlPct = if (values.isNotEmpty()) {
                    values.average()
                } else {
                    0.0
                },
                avgR = if (rValues.isNotEmpty()) {
                    rValues.average()
                } else {
                    0.0
                }
            )
        }.sortedWith(
            compareByDescending<IndicatorPerformance> { it.winRatePct }
                .thenByDescending { it.confirmedSignals }
        )
    }
}
