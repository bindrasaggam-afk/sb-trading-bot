package com.sb.researchanalytics.domain

import com.sb.backend.models.OrderStatus
import com.sb.researchanalytics.export.ResearchExportBuilder
import com.sb.signals.domain.SignalLifecycleStatus
import kotlin.math.abs
import kotlin.math.max

object ResearchAnalyticsEngine {

    private val indicatorKeys = listOf(
        "EMA trend",
        "RSI",
        "MACD",
        "Supertrend",
        "ADX",
        "VWAP",
        "Volume",
        "Breakout",
        "Bollinger",
        "Higher timeframe",
        "Trend timeframe"
    )

    fun analyze(dataset: AnalyticsDataset): ResearchAnalyticsReport {
        val rows = dataset.rows
        val research = summarizeResearch(rows)
        val paper = summarizePaper(rows)
        val indicators = indicatorStats(rows)
        val scoreBuckets = indicatorScoreBuckets(rows)
        val combinations = combinationStats(rows)
        val strategies = groups(
            rows,
            key = { it.signal.strategyId },
            label = { it.signal.strategyId }
        )
        val categories = groups(
            rows,
            key = { it.signal.category.name },
            label = { it.signal.category.label }
        )
        val directions = groups(
            rows,
            key = { it.signal.direction.name },
            label = { it.signal.direction.label }
        )
        val timeframes = groups(
            rows,
            key = { it.signal.timeframe },
            label = { it.signal.timeframe }
        )
        val symbols = groups(
            rows,
            key = { it.signal.symbol.uppercase() },
            label = { it.signal.symbol.uppercase() }
        )
        val holdingPeriods = groups(
            rows,
            key = { it.signal.holdingPeriod.name },
            label = { it.signal.holdingPeriod.label }
        )
        val marketConditions = marketConditionStats(rows)
        val entryTiming = entryTiming(rows)
        val exits = exitStats(rows)
        val dataQuality = dataQuality(dataset)
        val reconciliation = reconcile(dataset)

        val base = ResearchAnalyticsReport(
            dataset = dataset,
            research = research,
            paper = paper,
            indicators = indicators,
            scoreBuckets = scoreBuckets,
            combinations = combinations,
            strategies = strategies,
            categories = categories,
            directions = directions,
            timeframes = timeframes,
            symbols = symbols,
            holdingPeriods = holdingPeriods,
            marketConditions = marketConditions,
            entryTiming = entryTiming,
            exits = exits,
            dataQuality = dataQuality,
            reconciliation = reconciliation,
            sheets = emptyList()
        )
        return base.copy(
            sheets = ResearchExportBuilder.buildSheets(base)
        )
    }

    private fun summarizeResearch(
        rows: List<SignalAnalysisRow>
    ): PerformanceSummary {
        val completedRows = rows.filter { it.researchCompleted }
        val wins = completedRows.count { it.researchWin == true }
        val losses = completedRows.count { it.researchWin == false }
        val evaluated = wins + losses
        val invalidated = rows.count {
            it.signal.status == SignalLifecycleStatus.INVALIDATED
        }
        val pending = rows.count {
            !it.researchCompleted &&
                it.signal.status != SignalLifecycleStatus.INVALIDATED
        }
        val returns = completedRows.mapNotNull { it.researchReturnPct }
        val rValues = completedRows.mapNotNull { it.researchR }
        val positive = returns.filter { it > 0.0 }.sum()
        val negative = returns.filter { it < 0.0 }.sum()
        val ordered = completedRows.sortedBy {
            it.signal.closedAt ?: it.signal.generatedAt
        }
        val drawdown = maxDrawdown(
            ordered.mapNotNull { it.researchReturnPct }
        )
        val holding = completedRows.mapNotNull { row ->
            AnalyticsTime.durationHours(
                row.signal.triggeredAt ?: row.signal.generatedAt,
                row.signal.closedAt ?: row.signal.lastEvaluatedAt
            )
        }
        return PerformanceSummary(
            total = rows.size,
            completed = completedRows.size,
            wins = wins,
            losses = losses,
            pending = pending,
            invalidated = invalidated,
            winRate = ratio(wins, evaluated),
            averageReturnPct = average(returns),
            averageR = average(rValues),
            totalPnl = returns.sum(),
            profitFactor = if (negative < 0.0) {
                positive / abs(negative)
            } else if (positive > 0.0) {
                null
            } else {
                null
            },
            expectancyPct = average(returns),
            maxDrawdownPct = drawdown,
            averageHoldingHours = average(holding)
        )
    }

    private fun summarizePaper(
        rows: List<SignalAnalysisRow>
    ): PerformanceSummary {
        val closedRows = rows.filter { it.paperClosed }
        val wins = closedRows.count { it.paperWin == true }
        val losses = closedRows.count { it.paperWin == false }
        val evaluated = wins + losses
        val returns = closedRows.mapNotNull(::paperReturnPct)
        val pnl = closedRows.mapNotNull { it.paperNetPnl }
        val positive = pnl.filter { it > 0.0 }.sum()
        val negative = pnl.filter { it < 0.0 }.sum()
        val ordered = closedRows.sortedBy { row ->
            row.closedPaperOrders.maxOfOrNull { it.updatedAt } ?: 0L
        }
        val drawdown = maxDrawdown(returns)
        val holding = closedRows.mapNotNull { row ->
            val order = row.closedPaperOrders.maxByOrNull {
                it.updatedAt
            } ?: return@mapNotNull null
            AnalyticsTime.durationHours(order.createdAt, order.updatedAt)
        }
        return PerformanceSummary(
            total = rows.size,
            completed = closedRows.size,
            wins = wins,
            losses = losses,
            pending = rows.size - closedRows.size,
            winRate = ratio(wins, evaluated),
            averageReturnPct = average(returns),
            averageR = null,
            totalPnl = pnl.sum(),
            profitFactor = if (negative < 0.0) {
                positive / abs(negative)
            } else {
                null
            },
            expectancyPct = average(returns),
            maxDrawdownPct = drawdown,
            averageHoldingHours = average(holding)
        )
    }

    private fun indicatorStats(
        rows: List<SignalAnalysisRow>
    ): List<IndicatorStateStat> {
        val result = ArrayList<IndicatorStateStat>()
        indicatorKeys.forEach { indicator ->
            val scored = rows.filter {
                it.snapshot.indicator.scores[indicator] != null
            }
            val averageScore = average(
                scored.mapNotNull {
                    it.snapshot.indicator.scores[indicator]
                }
            )
            val averageConfidence = average(
                scored.map { it.signal.confidence }
            )
            val states = rows.mapNotNull { row ->
                row.snapshot.indicator.confirmations[indicator]
                    ?.let { it to row }
            }
            listOf(true, false).forEach { state ->
                val stateRows = states.filter { it.first == state }
                    .map { it.second }
                if (stateRows.isNotEmpty()) {
                    result.add(
                        IndicatorStateStat(
                            indicator = indicator,
                            confirmed = state,
                            summary = summarizeResearch(stateRows),
                            averageScore = averageScore,
                            averageConfidence = averageConfidence
                        )
                    )
                }
            }
        }
        return result
    }

    private fun indicatorScoreBuckets(
        rows: List<SignalAnalysisRow>
    ): List<IndicatorScoreBucketStat> {
        val result = ArrayList<IndicatorScoreBucketStat>()
        indicatorKeys.forEach { indicator ->
            val grouped = HashMap<String, MutableList<SignalAnalysisRow>>()
            rows.forEach { row ->
                val score = row.snapshot.indicator.scores[indicator]
                    ?: return@forEach
                grouped.getOrPut(scoreBucket(score)) {
                    ArrayList()
                }.add(row)
            }
            grouped.toSortedMap().forEach { (bucket, bucketRows) ->
                result.add(
                    IndicatorScoreBucketStat(
                        indicator = indicator,
                        bucket = bucket,
                        summary = summarizeResearch(bucketRows)
                    )
                )
            }
        }
        return result
    }

    private fun combinationStats(
        rows: List<SignalAnalysisRow>
    ): List<CombinationStat> {
        val grouped = HashMap<Pair<String, String>, MutableList<SignalAnalysisRow>>()
        rows.forEach { row ->
            val confirmed = indicatorKeys.filter {
                row.snapshot.indicator.confirmations[it] == true
            }
            if (confirmed.size < 2) return@forEach
            for (first in confirmed.indices) {
                for (second in first + 1 until confirmed.size) {
                    val key = confirmed[first] to confirmed[second]
                    grouped.getOrPut(key) { ArrayList() }.add(row)
                }
            }
        }
        return grouped.map { (key, comboRows) ->
            CombinationStat(
                combination = "${key.first} + ${key.second}",
                confirmedIndicators = 2,
                summary = summarizeResearch(comboRows)
            )
        }.filter { it.summary.total >= 2 }
            .sortedWith(
                compareByDescending<CombinationStat> {
                    it.summary.total
                }.thenByDescending {
                    it.summary.winRate ?: -1.0
                }
            )
            .take(60)
    }

    private fun groups(
        rows: List<SignalAnalysisRow>,
        key: (SignalAnalysisRow) -> String,
        label: (SignalAnalysisRow) -> String
    ): List<GroupPerformance> {
        return rows.groupBy(key).map { (groupKey, groupRows) ->
            GroupPerformance(
                key = groupKey,
                label = label(groupRows.first()),
                summary = summarizeResearch(groupRows)
            )
        }.sortedWith(
            compareByDescending<GroupPerformance> {
                it.summary.total
            }.thenBy { it.label }
        )
    }

    private fun marketConditionStats(
        rows: List<SignalAnalysisRow>
    ): List<MarketConditionStat> {
        val result = ArrayList<MarketConditionStat>()
        val dimensions: List<
            Pair<String, (SignalAnalysisRow) -> String>
            > = listOf(
            "ADX" to { row: SignalAnalysisRow ->
                row.snapshot.market.adxBucket
            },
            "Volatility" to { row: SignalAnalysisRow ->
                row.snapshot.market.volatilityBucket
            },
            "Volume" to { row: SignalAnalysisRow ->
                row.snapshot.market.volumeBucket
            }
        )
        dimensions.forEach { (dimension, bucketSelector) ->
            rows.groupBy(bucketSelector).forEach { (bucket, bucketRows) ->
                if (bucket != MarketSnapshotRecord.UNAVAILABLE) {
                    result.add(
                        MarketConditionStat(
                            dimension = dimension,
                            bucket = bucket,
                            summary = summarizeResearch(bucketRows)
                        )
                    )
                }
            }
        }
        return result.sortedWith(
            compareBy<MarketConditionStat> { it.dimension }
                .thenByDescending { it.summary.total }
        )
    }

    private fun entryTiming(
        rows: List<SignalAnalysisRow>
    ): List<EntryTimingStat> {
        val rangeWidth = ArrayList<Double>()
        val stopDistance = ArrayList<Double>()
        val rewardRisk = ArrayList<Double>()
        val triggerAge = ArrayList<Double>()
        val fillSlippage = ArrayList<Double>()
        val timeToClose = ArrayList<Double>()
        var insideEntry = 0
        var insideEntrySample = 0

        rows.forEach { row ->
            val signal = row.signal
            if (signal.referencePrice > 0.0 &&
                signal.entryHigh > signal.entryLow
            ) {
                rangeWidth.add(
                    (signal.entryHigh - signal.entryLow) /
                        signal.referencePrice * 100.0
                )
                if (signal.referencePrice in
                    signal.entryLow..signal.entryHigh
                ) {
                    insideEntry++
                }
                insideEntrySample++
            }
            if (signal.referencePrice > 0.0 &&
                signal.stopLoss > 0.0
            ) {
                stopDistance.add(
                    abs(signal.referencePrice - signal.stopLoss) /
                        signal.referencePrice * 100.0
                )
            }
            if (signal.riskPerUnit > 0.0) {
                rewardRisk.add(
                    abs(signal.target1 - signal.referencePrice) /
                        signal.riskPerUnit
                )
            }
            AnalyticsTime.durationHours(
                signal.generatedAt,
                signal.triggeredAt
            )?.let { triggerAge.add(it) }
            val fill = signal.entryFillPrice
            if (fill != null && signal.referencePrice > 0.0) {
                val direction = if (signal.direction.name == "BUY") {
                    1.0
                } else {
                    -1.0
                }
                fillSlippage.add(
                    (fill - signal.referencePrice) /
                        signal.referencePrice * 100.0 * direction
                )
            }
            AnalyticsTime.durationHours(
                signal.triggeredAt ?: signal.generatedAt,
                signal.closedAt
            )?.let { timeToClose.add(it) }
        }
        return listOf(
            EntryTimingStat(
                "entry_range",
                "Average entry range width %",
                rangeWidth.size,
                average(rangeWidth)
            ),
            EntryTimingStat(
                "reference_inside",
                "Reference price inside entry range %",
                insideEntrySample,
                if (insideEntrySample > 0) {
                    insideEntry.toDouble() / insideEntrySample * 100.0
                } else {
                    null
                }
            ),
            EntryTimingStat(
                "stop_distance",
                "Average stop distance %",
                stopDistance.size,
                average(stopDistance)
            ),
            EntryTimingStat(
                "reward_risk",
                "Average target 1 reward:risk",
                rewardRisk.size,
                average(rewardRisk)
            ),
            EntryTimingStat(
                "trigger_age",
                "Average trigger age hours",
                triggerAge.size,
                average(triggerAge)
            ),
            EntryTimingStat(
                "fill_slippage",
                "Average adverse fill slippage %",
                fillSlippage.size,
                average(fillSlippage)
            ),
            EntryTimingStat(
                "time_to_close",
                "Average time to close hours",
                timeToClose.size,
                average(timeToClose)
            )
        )
    }

    private fun exitStats(
        rows: List<SignalAnalysisRow>
    ): List<GroupPerformance> {
        return rows.filter { it.signal.status.isCompleted }
            .groupBy { it.signal.status.label }
            .map { (label, groupRows) ->
                GroupPerformance(
                    key = label,
                    label = label,
                    summary = summarizeResearch(groupRows)
                )
            }
            .sortedByDescending { it.summary.total }
    }

    private fun dataQuality(
        dataset: AnalyticsDataset
    ): DataQualitySummary {
        val rows = dataset.rows
        val captured = rows.count {
            it.snapshotSource ==
                IndicatorSnapshotRecord.SOURCE_CAPTURED
        }
        val reconstructed = rows.count {
            it.snapshotSource ==
                IndicatorSnapshotRecord.SOURCE_RECONSTRUCTED
        }
        val unavailable = rows.count {
            it.snapshotSource ==
                IndicatorSnapshotRecord.SOURCE_NOT_AVAILABLE
        }
        val incomplete = rows.count { row ->
            val snapshot = row.snapshot.indicator
            snapshot.values.isEmpty() ||
                snapshot.scores.isEmpty() ||
                snapshot.confirmations.isEmpty() ||
                snapshot.emaFast == null ||
                snapshot.rsi == null ||
                snapshot.macd == null ||
                snapshot.adx == null ||
                snapshot.atr == null ||
                snapshot.volume == null
        }
        val missingValues = rows.count {
            it.snapshot.indicator.values.isEmpty()
        }
        val missingScores = rows.count {
            it.snapshot.indicator.scores.isEmpty()
        }
        val duplicateKeys = rows.groupBy { it.signal.dedupeKey }
            .count { it.value.size > 1 }
        val ambiguous = rows.count { it.signal.ambiguousBar }
        val qualityScores = rows.mapNotNull {
            it.signal.dataQuality.score.takeIf { score ->
                score.isFinite()
            }
        }
        val orphanOrders = dataset.allPaperOrders.count { order ->
            val signalId = order.signalId
            signalId == null || signalId !in dataset.allSignalIds
        }
        val issues = ArrayList<DataQualityIssue>()
        if (dataset.snapshotTableError != null) {
            issues.add(
                DataQualityIssue(
                    severity = DataQualityIssue.ERROR,
                    category = "Snapshots",
                    message = "Snapshot table unavailable: " +
                        dataset.snapshotTableError,
                    affectedSignals = rows.size
                )
            )
        }
        if (reconstructed > 0) {
            issues.add(
                DataQualityIssue(
                    severity = DataQualityIssue.WARNING,
                    category = "Historical data",
                    message = "Legacy snapshots are reconstructed from " +
                        "stored signal JSON and are labelled accordingly. " +
                        "No values were recalculated.",
                    affectedSignals = reconstructed
                )
            )
        }
        if (unavailable > 0) {
            issues.add(
                DataQualityIssue(
                    severity = DataQualityIssue.WARNING,
                    category = "Historical data",
                    message = "No indicator snapshot is available for " +
                        "these signals.",
                    affectedSignals = unavailable
                )
            )
        }
        if (missingScores > 0) {
            issues.add(
                DataQualityIssue(
                    severity = DataQualityIssue.WARNING,
                    category = "Scores",
                    message = "Per-indicator scores are missing. " +
                        "They are marked NOT_AVAILABLE, not inferred.",
                    affectedSignals = missingScores
                )
            )
        }
        if (duplicateKeys > 0) {
            issues.add(
                DataQualityIssue(
                    severity = DataQualityIssue.WARNING,
                    category = "Duplicates",
                    message = "Duplicate signal identity keys were found.",
                    affectedSignals = duplicateKeys
                )
            )
        }
        if (orphanOrders > 0) {
            issues.add(
                DataQualityIssue(
                    severity = DataQualityIssue.WARNING,
                    category = "Reconciliation",
                    message = "Paper orders do not link to a research signal.",
                    affectedSignals = orphanOrders
                )
            )
        }
        if (ambiguous > 0) {
            issues.add(
                DataQualityIssue(
                    severity = DataQualityIssue.WARNING,
                    category = "Candles",
                    message = "Outcome bars were ambiguous; stop-first " +
                        "evaluation is retained for research.",
                    affectedSignals = ambiguous
                )
            )
        }
        if (rows.any { it.signal.dataQuality.score < 70.0 }) {
            issues.add(
                DataQualityIssue(
                    severity = DataQualityIssue.WARNING,
                    category = "Candles",
                    message = "One or more signals have low candle data " +
                        "quality. Values are shown as recorded.",
                    affectedSignals = rows.count {
                        it.signal.dataQuality.score < 70.0
                    }
                )
            )
        }
        return DataQualitySummary(
            totalSignals = rows.size,
            capturedSnapshots = captured,
            reconstructedSnapshots = reconstructed,
            unavailableSnapshots = unavailable,
            incompleteSnapshots = incomplete,
            missingIndicatorValues = missingValues,
            missingScores = missingScores,
            duplicateSignalKeys = duplicateKeys,
            ambiguousBars = ambiguous,
            averageDataQualityScore = average(qualityScores),
            issues = issues
        )
    }

    private fun reconcile(
        dataset: AnalyticsDataset
    ): ReconciliationSummary {
        val rows = dataset.rows
        val researchIds = dataset.allSignalIds
        val paperSignalIds = dataset.allPaperOrders
            .mapNotNull { it.signalId }
            .toSet()
        val linkedRows = rows.filter { it.paperOrders.isNotEmpty() }
        val agreements = linkedRows.count {
            it.reconciliationStatus == "Agreement"
        }
        val mismatches = linkedRows.count {
            it.reconciliationStatus == "Outcome mismatch"
        }
        val pnlMismatches = linkedRows.count { row ->
            val research = row.researchReturnPct
            val paper = paperReturnPct(row)
            research != null && paper != null &&
                abs(research - paper) > 0.5
        }
        val orphanOrders = dataset.allPaperOrders.count { order ->
            order.signalId == null ||
                order.signalId !in researchIds
        }
        val notes = ArrayList<String>()
        notes.add(
            "Research outcomes use candle/stop-first evaluation and " +
                "produce R multiples."
        )
        notes.add(
            "Paper outcomes use live/paper LTP, slippage, charges, " +
                "partial targets and execution order."
        )
        notes.add(
            "Pending paper positions are not merged into research " +
                "outcomes."
        )
        if (paperSignalIds.isEmpty() && rows.isNotEmpty()) {
            notes.add(
                "No paper orders are linked to the selected signals yet."
            )
        }
        return ReconciliationSummary(
            researchSignals = rows.size,
            paperOrders = dataset.allPaperOrders.size,
            linkedSignals = linkedRows.size,
            researchWithoutPaper = rows.count {
                it.paperOrders.isEmpty()
            },
            paperPendingWithoutExecution = rows.count {
                it.paperPending && it.paperExecutions.isEmpty()
            },
            paperClosedWithResearchOpen = rows.count {
                it.paperClosed && !it.researchCompleted
            },
            researchClosedWithoutPaperClose = rows.count {
                it.researchCompleted && !it.paperClosed
            },
            orphanPaperOrders = orphanOrders,
            outcomeAgreements = agreements,
            outcomeMismatches = mismatches,
            pnlMismatches = pnlMismatches,
            researchOnlyPnlPct = average(
                rows.mapNotNull { it.researchReturnPct }
            ),
            paperRealizedPnl = dataset.paperAccount?.realizedPnl
                ?: dataset.allPaperOrders
                    .filter {
                        it.status == OrderStatus.CLOSED.name
                    }
                    .sumOf { it.realizedPnl ?: 0.0 },
            notes = notes
        )
    }

    fun paperReturnPct(row: SignalAnalysisRow): Double? {
        val net = row.paperNetPnl ?: return null
        val value = row.closedPaperOrders.sumOf {
            abs(it.quantity.toDouble() * it.entryPrice)
        }
        if (value <= 0.0) return null
        return net / value * 100.0
    }

    private fun maxDrawdown(returns: List<Double>): Double? {
        if (returns.isEmpty()) return null
        var equity = 0.0
        var peak = 0.0
        var maxDrawdown = 0.0
        returns.forEach { value ->
            equity += value
            if (equity > peak) peak = equity
            val drawdown = peak - equity
            if (drawdown > maxDrawdown) maxDrawdown = drawdown
        }
        return maxDrawdown
    }

    private fun scoreBucket(score: Double): String = when {
        score < 25.0 -> "0-24"
        score < 50.0 -> "25-49"
        score < 75.0 -> "50-74"
        else -> "75-100"
    }

    private fun average(values: List<Double>): Double? =
        if (values.isEmpty()) null else values.sum() / values.size

    private fun ratio(numerator: Int, denominator: Int): Double? =
        if (denominator <= 0) {
            null
        } else {
            numerator.toDouble() / denominator * 100.0
        }
}
