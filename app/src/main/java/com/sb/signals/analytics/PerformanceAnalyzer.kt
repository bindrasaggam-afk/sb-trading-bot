package com.sb.signals.analytics

import com.sb.backend.market.TimeframeBuckets
import com.sb.signals.domain.ResearchSignal
import com.sb.signals.domain.SignalLifecycleStatus
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class PerformanceSlice(
    val label: String,
    val total: Int,
    val wins: Int,
    val losses: Int,
    val breakeven: Int,
    val winRatePct: Double,
    val avgNetPnlPct: Double,
    val avgR: Double
)

data class PerformanceStats(
    val generated: Int,
    val completed: Int,
    val evaluated: Int,
    val open: Int,
    val invalidated: Int,
    val triggered: Int,
    val untriggeredExpired: Int,
    val triggerRatePct: Double,
    val wins: Int,
    val losses: Int,
    val breakeven: Int,
    val winRatePct: Double,
    val directionalAccuracyPct: Double,
    val coveragePct: Double,
    val profitFactor: Double,
    val avgWinPct: Double,
    val avgLossPct: Double,
    val avgNetPnlPct: Double,
    val expectancyR: Double,
    val maxDrawdownPct: Double,
    val maxConsecutiveLosses: Int,
    val completionRatePct: Double,
    val byCategory: Map<String, PerformanceSlice>,
    val byDirection: Map<String, PerformanceSlice>,
    val byHoldingPeriod: Map<String, PerformanceSlice>,
    val byTimeframe: Map<String, PerformanceSlice>,
    val byStrategy: Map<String, PerformanceSlice>,
    val byVolatility: Map<String, PerformanceSlice>,
    val byDataQuality: Map<String, PerformanceSlice>,
    val byHigherTimeframeTrend: Map<String, PerformanceSlice>,
    val byConfidence: Map<String, PerformanceSlice>,
    val bySymbol: Map<String, PerformanceSlice>,
    val byHour: Map<String, PerformanceSlice>,
    val byMonth: Map<String, PerformanceSlice>
)

object PerformanceAnalyzer {

    fun analyze(signals: List<ResearchSignal>): PerformanceStats {
        val completed = signals.filter { it.status.isCompleted }
        val traded = completed.filter {
            it.outcomeNetPnlPercent != null
        }
        val wins = traded.count {
            (it.outcomeNetPnlPercent ?: 0.0) > 0.001
        }
        val losses = traded.count {
            (it.outcomeNetPnlPercent ?: 0.0) < -0.001
        }
        val breakeven = traded.size - wins - losses
        val winRate = if (traded.isNotEmpty()) {
            wins.toDouble() / traded.size * 100.0
        } else {
            0.0
        }
        val directionalAccuracy = if (wins + losses > 0) {
            wins.toDouble() / (wins + losses) * 100.0
        } else {
            0.0
        }
        val coverage = if (signals.isNotEmpty()) {
            traded.size.toDouble() / signals.size * 100.0
        } else {
            0.0
        }
        val triggered = signals.count { it.triggeredAt != null }
        val untriggeredExpired = signals.count {
            it.status == SignalLifecycleStatus.EXPIRED &&
                it.triggeredAt == null
        }
        val triggerRate = if (signals.isNotEmpty()) {
            triggered.toDouble() / signals.size * 100.0
        } else {
            0.0
        }
        val grossWins = traded.mapNotNull { it.outcomeNetPnlPercent }
            .filter { it > 0.0 }
            .sum()
        val grossLosses = traded.mapNotNull { it.outcomeNetPnlPercent }
            .filter { it < 0.0 }
            .sum()
        val profitFactor = when {
            abs(grossLosses) > 0.000001 -> grossWins / abs(grossLosses)
            grossWins > 0.0 -> Double.POSITIVE_INFINITY
            else -> 0.0
        }
        val avgWin = if (wins > 0) {
            traded.filter { (it.outcomeNetPnlPercent ?: 0.0) > 0.0 }
                .mapNotNull { it.outcomeNetPnlPercent }
                .average()
        } else {
            0.0
        }
        val avgLoss = if (losses > 0) {
            traded.filter { (it.outcomeNetPnlPercent ?: 0.0) < 0.0 }
                .mapNotNull { it.outcomeNetPnlPercent }
                .average()
        } else {
            0.0
        }
        val avgNet = if (traded.isNotEmpty()) {
            traded.mapNotNull { it.outcomeNetPnlPercent }.average()
        } else {
            0.0
        }
        val rValues = traded.mapNotNull { it.outcomeR }
        val expectancyR = if (rValues.isNotEmpty()) {
            rValues.average()
        } else {
            0.0
        }

        val ordered = traded.sortedWith(
            compareBy<ResearchSignal> { it.closedAt ?: it.generatedAt }
                .thenBy { it.id }
        )
        var equity = 100.0
        var peak = equity
        var maxDrawdown = 0.0
        var consecutiveLosses = 0
        var maxConsecutiveLosses = 0

        ordered.forEach { signal ->
            val net = signal.outcomeNetPnlPercent ?: 0.0
            equity *= 1.0 + net / 100.0
            peak = max(peak, equity)
            if (peak > 0.0) {
                maxDrawdown = max(
                    maxDrawdown,
                    (peak - equity) / peak * 100.0
                )
            }
            if (net < -0.001) {
                consecutiveLosses++
                maxConsecutiveLosses = max(
                    maxConsecutiveLosses,
                    consecutiveLosses
                )
            } else {
                consecutiveLosses = 0
            }
        }

        return PerformanceStats(
            generated = signals.size,
            completed = completed.size,
            evaluated = traded.size,
            open = signals.count { it.status.isOpen },
            invalidated = signals.count {
                it.status == SignalLifecycleStatus.INVALIDATED
            },
            triggered = triggered,
            untriggeredExpired = untriggeredExpired,
            triggerRatePct = triggerRate,
            wins = wins,
            losses = losses,
            breakeven = breakeven,
            winRatePct = winRate,
            directionalAccuracyPct = directionalAccuracy,
            coveragePct = coverage,
            profitFactor = profitFactor,
            avgWinPct = avgWin,
            avgLossPct = avgLoss,
            avgNetPnlPct = avgNet,
            expectancyR = expectancyR,
            maxDrawdownPct = maxDrawdown,
            maxConsecutiveLosses = maxConsecutiveLosses,
            completionRatePct = if (signals.isNotEmpty()) {
                completed.size.toDouble() / signals.size * 100.0
            } else {
                0.0
            },
            byCategory = group(traded, "ALL") { it.category.label },
            byDirection = group(traded, "ALL") { it.direction.label },
            byHoldingPeriod = group(traded, "ALL") {
                it.holdingPeriod.label
            },
            byTimeframe = group(traded, "ALL") { it.timeframe },
            byStrategy = group(traded, "ALL") {
                "${it.strategyId} v${it.strategyVersion}"
            },
            byVolatility = group(traded, "ALL") { volatilityBucket(it) },
            byDataQuality = group(traded, "ALL") { qualityBucket(it) },
            byHigherTimeframeTrend = group(traded, "ALL") {
                if (it.indicatorSnapshot.confirmations[
                        "Higher timeframe"
                    ] == true
                ) {
                    "Higher trend aligned"
                } else {
                    "No higher trend confirmation"
                }
            },
            byConfidence = group(traded, "ALL") {
                confidenceBucket(it)
            },
            bySymbol = group(traded, "ALL") {
                "${it.exchange}:${it.symbol}"
            },
            byHour = group(traded, "ALL") { hourLabel(it.generatedAt) },
            byMonth = group(traded, "ALL") {
                monthLabel(it.closedAt ?: it.generatedAt)
            }
        )
    }

    private fun group(
        signals: List<ResearchSignal>,
        allLabel: String,
        key: (ResearchSignal) -> String
    ): Map<String, PerformanceSlice> {
        val grouped = linkedMapOf<String, MutableList<ResearchSignal>>()
        signals.forEach {
            grouped.getOrPut(key(it)) { ArrayList() }.add(it)
        }
        return grouped.mapValues { (label, values) ->
            slice(label, values)
        }.toList().sortedBy { it.first }.toMap(
            linkedMapOf(allLabel to slice(allLabel, signals))
        )
    }

    private fun slice(
        label: String,
        signals: List<ResearchSignal>
    ): PerformanceSlice {
        val values = signals.mapNotNull { it.outcomeNetPnlPercent }
        val wins = values.count { it > 0.001 }
        val losses = values.count { it < -0.001 }
        val breakeven = values.size - wins - losses
        val rValues = signals.mapNotNull { it.outcomeR }
        return PerformanceSlice(
            label = label,
            total = values.size,
            wins = wins,
            losses = losses,
            breakeven = breakeven,
            winRatePct = if (values.isNotEmpty()) {
                wins.toDouble() / values.size * 100.0
            } else {
                0.0
            },
            avgNetPnlPct = if (values.isNotEmpty()) values.average() else 0.0,
            avgR = if (rValues.isNotEmpty()) rValues.average() else 0.0
        )
    }

    private fun volatilityBucket(signal: ResearchSignal): String {
        val atr = signal.indicatorSnapshot.values.entries
            .firstOrNull { it.key.startsWith("ATR") }
            ?.value
        val close = signal.indicatorSnapshot.values["Close"]
        if (atr == null || close == null || close <= 0.0) {
            return "Unknown volatility"
        }
        val percent = atr / close * 100.0
        return when {
            percent < 1.0 -> "Low volatility (<1% ATR)"
            percent < 2.5 -> "Medium volatility (1-2.5% ATR)"
            else -> "High volatility (>2.5% ATR)"
        }
    }

    private fun confidenceBucket(signal: ResearchSignal): String {
        return when {
            signal.confidence >= 90.0 -> "Confidence 90-100"
            signal.confidence >= 80.0 -> "Confidence 80-89"
            signal.confidence >= 70.0 -> "Confidence 70-79"
            signal.confidence >= 60.0 -> "Confidence 60-69"
            else -> "Confidence below 60"
        }
    }

    private fun qualityBucket(signal: ResearchSignal): String {
        val score = signal.dataQuality.score
        return when {
            score >= 90.0 -> "Data quality 90-100"
            score >= 70.0 -> "Data quality 70-89"
            else -> "Data quality below 70"
        }
    }

    private fun hourLabel(timestamp: Long): String {
        val calendar = Calendar.getInstance(TimeframeBuckets.IST).apply {
            timeInMillis = timestamp
        }
        return String.format(
            Locale.ROOT,
            "%02d:00",
            calendar.get(Calendar.HOUR_OF_DAY)
        )
    }

    private fun monthLabel(timestamp: Long): String {
        val calendar = Calendar.getInstance(TimeframeBuckets.IST).apply {
            timeInMillis = timestamp
        }
        return String.format(
            Locale.ROOT,
            "%04d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1
        )
    }
}
