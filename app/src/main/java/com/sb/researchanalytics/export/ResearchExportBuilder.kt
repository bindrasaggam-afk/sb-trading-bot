package com.sb.researchanalytics.export

import com.sb.researchanalytics.domain.AnalyticsTime
import com.sb.researchanalytics.domain.ExportSheet
import com.sb.researchanalytics.domain.GroupPerformance
import com.sb.researchanalytics.domain.PerformanceSummary
import com.sb.researchanalytics.domain.ResearchAnalyticsReport
import com.sb.researchanalytics.domain.SignalAnalysisRow

object ResearchExportBuilder {

    fun buildSheets(
        report: ResearchAnalyticsReport
    ): List<ExportSheet> = listOf(
        summarySheet(report),
        signalOutcomeSheet(report),
        indicatorSnapshotSheet(report),
        indicatorAnalysisSheet(report),
        indicatorScoreSheet(report),
        combinationSheet(report),
        strategySheet(report),
        entryTimingSheet(report),
        exitSheet(report),
        marketConditionSheet(report),
        dataQualitySheet(report),
        reconciliationSheet(report),
        paperOrdersSheet(report),
        paperExecutionsSheet(report),
        pendingSheet(report),
        closedSheet(report),
        metadataSheet(report)
    )

    private fun summarySheet(
        report: ResearchAnalyticsReport
    ): ExportSheet {
        val research = report.research
        val paper = report.paper
        val rows = listOf(
            listOf("Data through", time(report.dataset.dataThrough)),
            listOf("Generated at", time(report.dataset.generatedAt)),
            listOf("Filter range", report.dataset.filter.rangeLabel()),
            listOf("Selected signals", research.total),
            listOf("Research completed", research.completed),
            listOf("Research wins", research.wins),
            listOf("Research losses", research.losses),
            listOf("Research pending", research.pending),
            listOf("Research invalidated", research.invalidated),
            listOf("Research win rate %", num(research.winRate)),
            listOf("Research avg return %", num(research.averageReturnPct)),
            listOf("Research avg R", num(research.averageR)),
            listOf("Research profit factor", num(research.profitFactor)),
            listOf("Research max drawdown %", num(research.maxDrawdownPct)),
            listOf("Paper closed", paper.completed),
            listOf("Paper wins", paper.wins),
            listOf("Paper losses", paper.losses),
            listOf("Paper pending", paper.pending),
            listOf("Paper win rate %", num(paper.winRate)),
            listOf("Paper avg return %", num(paper.averageReturnPct)),
            listOf("Paper realized P&L", num(paper.totalPnl)),
            listOf("Paper profit factor", num(paper.profitFactor)),
            listOf("Paper max drawdown %", num(paper.maxDrawdownPct)),
            listOf(
                "Paper account balance",
                num(report.dataset.paperAccount?.balance)
            ),
            listOf(
                "Paper available balance",
                num(report.dataset.paperAccount?.availableBalance)
            ),
            listOf(
                "Captured snapshots",
                report.dataQuality.capturedSnapshots
            ),
            listOf(
                "Reconstructed snapshots",
                report.dataQuality.reconstructedSnapshots
            ),
            listOf(
                "Unavailable snapshots",
                report.dataQuality.unavailableSnapshots
            )
        )
        return ExportSheet(
            name = "Summary",
            headers = listOf("Metric", "Value"),
            rows = rows
        )
    }

    private fun signalOutcomeSheet(
        report: ResearchAnalyticsReport
    ): ExportSheet {
        val headers = listOf(
            "Signal ID",
            "Symbol",
            "Exchange",
            "Category",
            "Direction",
            "Timeframe",
            "Strategy",
            "Strategy version",
            "Status",
            "Generated at",
            "Candle timestamp",
            "Reference price",
            "Entry low",
            "Entry high",
            "Stop loss",
            "Target 1",
            "Target 2",
            "Target 3",
            "Risk per unit",
            "Reward:risk 1",
            "Confidence",
            "Holding period",
            "Research state",
            "Research win",
            "Research return %",
            "Research R",
            "Outcome reason",
            "Ambiguous bar",
            "Paper state",
            "Paper orders",
            "Paper net P&L",
            "Paper win",
            "Reconciliation",
            "Snapshot source",
            "Data quality score",
            "Data quality notes"
        )
        val rows = report.dataset.rows.map { row ->
            val signal = row.signal
            listOf(
                signal.id,
                signal.symbol,
                signal.exchange,
                signal.category.label,
                signal.direction.label,
                signal.timeframe,
                signal.strategyId,
                signal.strategyVersion,
                signal.status.label,
                time(signal.generatedAt),
                time(signal.candleTimestamp),
                signal.referencePrice,
                signal.entryLow,
                signal.entryHigh,
                signal.stopLoss,
                signal.target1,
                signal.target2,
                signal.target3,
                signal.riskPerUnit,
                signal.rewardRisk1,
                signal.confidence,
                signal.holdingPeriod.label,
                row.researchState,
                bool(row.researchWin),
                num(row.researchReturnPct),
                num(row.researchR),
                signal.outcomeReason ?: AnalyticsTime.NOT_AVAILABLE,
                bool(signal.ambiguousBar),
                row.paperState,
                row.paperOrders.size,
                num(row.paperNetPnl),
                bool(row.paperWin),
                row.reconciliationStatus,
                row.snapshotSource,
                num(signal.dataQuality.score),
                signal.dataQuality.notes.joinToString(" | ")
                    .ifBlank { AnalyticsTime.NOT_AVAILABLE }
            )
        }
        return ExportSheet("Signal Outcomes", headers, rows)
    }

    private fun indicatorSnapshotSheet(
        report: ResearchAnalyticsReport
    ): ExportSheet {
        val headers = listOf(
            "Signal ID",
            "Symbol",
            "Snapshot source",
            "Captured at",
            "Candle timestamp",
            "Primary timeframe",
            "Confirmation timeframe",
            "Trend timeframe",
            "Strategy config",
            "Total score",
            "Bull score",
            "Bear score",
            "EMA fast",
            "EMA slow",
            "EMA trend",
            "RSI",
            "MACD",
            "MACD signal",
            "MACD histogram",
            "Supertrend",
            "Supertrend direction",
            "ADX",
            "Plus DI",
            "Minus DI",
            "VWAP",
            "BB upper",
            "BB middle",
            "BB lower",
            "ATR",
            "Volume",
            "Volume MA",
            "Volume ratio",
            "Breakout confirmed",
            "Higher time frame confirmed",
            "Trend time frame confirmed",
            "Indicator values JSON",
            "Indicator scores JSON",
            "Confirmations JSON",
            "Parameters JSON"
        )
        val rows = report.dataset.rows.map { row ->
            val indicator = row.snapshot.indicator
            listOf(
                row.signal.id,
                row.signal.symbol,
                indicator.source,
                time(indicator.capturedAt),
                time(indicator.candleTimestamp),
                indicator.primaryTimeframe,
                indicator.confirmationTimeframe,
                indicator.trendTimeframe,
                indicator.strategyConfigJson.ifBlank {
                    AnalyticsTime.NOT_AVAILABLE
                },
                num(indicator.totalScore),
                num(indicator.bullScore),
                num(indicator.bearScore),
                num(indicator.emaFast),
                num(indicator.emaSlow),
                num(indicator.emaTrend),
                num(indicator.rsi),
                num(indicator.macd),
                num(indicator.macdSignal),
                num(indicator.macdHistogram),
                num(indicator.supertrend),
                num(indicator.supertrendDirection),
                num(indicator.adx),
                num(indicator.plusDi),
                num(indicator.minusDi),
                num(indicator.vwap),
                num(indicator.bbUpper),
                num(indicator.bbMiddle),
                num(indicator.bbLower),
                num(indicator.atr),
                num(indicator.volume),
                num(indicator.volumeMa),
                num(indicator.volumeRatio),
                bool(indicator.breakoutConfirmed),
                bool(indicator.higherTimeframeConfirmed),
                bool(indicator.trendTimeframeConfirmed),
                JSON.of(indicator.values.mapValues { num(it.value) }),
                JSON.of(indicator.scores.mapValues { num(it.value) }),
                JSON.of(
                    indicator.confirmations.mapValues {
                        bool(it.value)
                    }
                ),
                JSON.of(indicator.parameters.mapValues { num(it.value) })
            )
        }
        return ExportSheet("Indicator Snapshots", headers, rows)
    }

    private fun indicatorAnalysisSheet(
        report: ResearchAnalyticsReport
    ): ExportSheet {
        val headers = listOf(
            "Indicator",
            "Confirmation",
            "Signals",
            "Completed",
            "Wins",
            "Losses",
            "Pending",
            "Win rate %",
            "Average return %",
            "Average R",
            "Profit factor",
            "Average score",
            "Average signal confidence"
        )
        val rows = report.indicators.map { stat ->
            summaryRow(
                listOf(
                    stat.indicator,
                    if (stat.confirmed) "TRUE" else "FALSE"
                ),
                stat.summary,
                averageScore = stat.averageScore,
                averageConfidence = stat.averageConfidence,
                includeIndicatorMetrics = true
            )
        }
        return ExportSheet("Indicator Analysis", headers, rows)
    }

    private fun indicatorScoreSheet(
        report: ResearchAnalyticsReport
    ): ExportSheet {
        val headers = listOf(
            "Indicator",
            "Score bucket",
            "Signals",
            "Completed",
            "Wins",
            "Losses",
            "Pending",
            "Win rate %",
            "Average return %",
            "Average R",
            "Profit factor"
        )
        val rows = report.scoreBuckets.map { stat ->
            summaryRow(
                listOf(stat.indicator, stat.bucket),
                stat.summary
            )
        }
        return ExportSheet("Indicator Scores", headers, rows)
    }

    private fun combinationSheet(
        report: ResearchAnalyticsReport
    ): ExportSheet {
        val headers = listOf(
            "Combination",
            "Confirmed indicators",
            "Signals",
            "Completed",
            "Wins",
            "Losses",
            "Win rate %",
            "Average return %",
            "Average R",
            "Profit factor"
        )
        val rows = report.combinations.map { stat ->
            summaryRow(
                listOf(stat.combination, stat.confirmedIndicators),
                stat.summary
            )
        }
        return ExportSheet("Indicator Combinations", headers, rows)
    }

    private fun strategySheet(
        report: ResearchAnalyticsReport
    ): ExportSheet {
        val headers = listOf(
            "Dimension",
            "Key",
            "Label",
            "Signals",
            "Completed",
            "Wins",
            "Losses",
            "Pending",
            "Win rate %",
            "Average return %",
            "Average R",
            "Profit factor",
            "Total return %"
        )
        val dimensions = listOf(
            "Strategy" to report.strategies,
            "Category" to report.categories,
            "Direction" to report.directions,
            "Timeframe" to report.timeframes,
            "Symbol" to report.symbols,
            "Holding period" to report.holdingPeriods
        )
        val rows = ArrayList<List<Any?>>()
        dimensions.forEach { (dimension, groups) ->
            groups.forEach { group ->
                rows.add(
                    summaryRow(
                        listOf(dimension, group.key, group.label),
                        group.summary,
                        includeTotal = true
                    )
                )
            }
        }
        return ExportSheet("Strategy Performance", headers, rows)
    }

    private fun entryTimingSheet(
        report: ResearchAnalyticsReport
    ): ExportSheet {
        val rows = report.entryTiming.map { stat ->
            listOf(
                stat.key,
                stat.label,
                stat.sample,
                num(stat.average)
            )
        }
        return ExportSheet(
            "Entry Timing",
            listOf("Metric key", "Metric", "Sample", "Average"),
            rows
        )
    }

    private fun exitSheet(
        report: ResearchAnalyticsReport
    ): ExportSheet {
        val headers = listOf(
            "Exit status",
            "Signals",
            "Wins",
            "Losses",
            "Win rate %",
            "Average return %",
            "Average R",
            "Profit factor"
        )
        val rows = report.exits.map { stat ->
            summaryRow(listOf(stat.label), stat.summary)
        }
        return ExportSheet("Exit Analysis", headers, rows)
    }

    private fun marketConditionSheet(
        report: ResearchAnalyticsReport
    ): ExportSheet {
        val headers = listOf(
            "Dimension",
            "Bucket",
            "Signals",
            "Completed",
            "Wins",
            "Losses",
            "Win rate %",
            "Average return %",
            "Average R",
            "Profit factor"
        )
        val rows = report.marketConditions.map { stat ->
            summaryRow(
                listOf(stat.dimension, stat.bucket),
                stat.summary
            )
        }
        return ExportSheet("Market Conditions", headers, rows)
    }

    private fun dataQualitySheet(
        report: ResearchAnalyticsReport
    ): ExportSheet {
        val quality = report.dataQuality
        val rows = ArrayList<List<Any?>>()
        rows.add(
            listOf(
                "Metric",
                "Total signals",
                quality.totalSignals,
                "",
                ""
            )
        )
        rows.add(
            listOf(
                "Metric",
                "Captured snapshots",
                quality.capturedSnapshots,
                "",
                ""
            )
        )
        rows.add(
            listOf(
                "Metric",
                "Reconstructed snapshots",
                quality.reconstructedSnapshots,
                "",
                "Derived only from stored signal JSON"
            )
        )
        rows.add(
            listOf(
                "Metric",
                "Unavailable snapshots",
                quality.unavailableSnapshots,
                "",
                "Marked NOT_AVAILABLE"
            )
        )
        rows.add(
            listOf(
                "Metric",
                "Incomplete snapshots",
                quality.incompleteSnapshots,
                "",
                ""
            )
        )
        rows.add(
            listOf(
                "Metric",
                "Missing indicator values",
                quality.missingIndicatorValues,
                "",
                ""
            )
        )
        rows.add(
            listOf(
                "Metric",
                "Missing indicator scores",
                quality.missingScores,
                "",
                ""
            )
        )
        rows.add(
            listOf(
                "Metric",
                "Duplicate signal keys",
                quality.duplicateSignalKeys,
                "",
                ""
            )
        )
        rows.add(
            listOf(
                "Metric",
                "Ambiguous bars",
                quality.ambiguousBars,
                "",
                "Research keeps stop-first evaluation"
            )
        )
        rows.add(
            listOf(
                "Metric",
                "Average data quality score",
                num(quality.averageDataQualityScore),
                "",
                ""
            )
        )
        quality.issues.forEach { issue ->
            rows.add(
                listOf(
                    "Issue",
                    issue.category,
                    issue.affectedSignals,
                    issue.severity,
                    issue.message
                )
            )
        }
        return ExportSheet(
            "Data Quality",
            listOf("Type", "Metric / category", "Value", "Severity", "Notes"),
            rows
        )
    }

    private fun reconciliationSheet(
        report: ResearchAnalyticsReport
    ): ExportSheet {
        val value = report.reconciliation
        val rows = listOf(
            listOf("Research signals", value.researchSignals),
            listOf("Paper orders", value.paperOrders),
            listOf("Linked research signals", value.linkedSignals),
            listOf("Research without paper", value.researchWithoutPaper),
            listOf(
                "Paper pending without execution",
                value.paperPendingWithoutExecution
            ),
            listOf(
                "Paper closed with research open",
                value.paperClosedWithResearchOpen
            ),
            listOf(
                "Research closed without paper close",
                value.researchClosedWithoutPaperClose
            ),
            listOf("Orphan paper orders", value.orphanPaperOrders),
            listOf("Outcome agreements", value.outcomeAgreements),
            listOf("Outcome mismatches", value.outcomeMismatches),
            listOf("P&L mismatches (>0.5%)", value.pnlMismatches),
            listOf(
                "Research-only average return %",
                num(value.researchOnlyPnlPct)
            ),
            listOf(
                "Paper realized P&L",
                num(value.paperRealizedPnl)
            )
        ) + value.notes.map { listOf("Note", it) }
        return ExportSheet(
            "Reconciliation",
            listOf("Metric", "Value"),
            rows
        )
    }

    private fun paperOrdersSheet(
        report: ResearchAnalyticsReport
    ): ExportSheet {
        val headers = listOf(
            "Paper order ID",
            "Signal ID",
            "Symbol",
            "Exchange",
            "Side",
            "Quantity",
            "Remaining quantity",
            "Entry price",
            "Exit price",
            "Status",
            "Entry source",
            "Entry timestamp",
            "Created at",
            "Updated at",
            "Stop loss",
            "Target",
            "Target 1",
            "Target 2",
            "Target 3",
            "Realized P&L",
            "Entry charges",
            "Charges",
            "Exit reason",
            "Strategy",
            "Strategy version",
            "Signal category",
            "Signal timeframe",
            "Signal generated at",
            "Reconciliation"
        )
        val rows = report.dataset.allPaperOrders.map { order ->
            val linked = order.signalId != null &&
                order.signalId in report.dataset.allSignalIds
            listOf(
                order.id,
                order.signalId ?: AnalyticsTime.NOT_AVAILABLE,
                order.symbol,
                order.exchange,
                order.side,
                order.quantity,
                order.remainingQuantity,
                order.entryPrice,
                num(order.exitPrice),
                order.status,
                order.entrySource,
                time(order.entryTimestamp),
                time(order.createdAt),
                time(order.updatedAt),
                num(order.stopLoss),
                num(order.target),
                num(order.target1),
                num(order.target2),
                num(order.target3),
                num(order.realizedPnl),
                num(order.entryCharges),
                num(order.charges),
                order.exitReason.ifBlank {
                    AnalyticsTime.NOT_AVAILABLE
                },
                order.strategyId.ifBlank {
                    AnalyticsTime.NOT_AVAILABLE
                },
                order.strategyVersion.ifBlank {
                    AnalyticsTime.NOT_AVAILABLE
                },
                order.signalCategory.ifBlank {
                    AnalyticsTime.NOT_AVAILABLE
                },
                order.signalTimeframe.ifBlank {
                    AnalyticsTime.NOT_AVAILABLE
                },
                time(order.signalGeneratedAt),
                if (linked) "Linked" else "Unlinked"
            )
        }
        return ExportSheet("Paper Orders", headers, rows)
    }

    private fun paperExecutionsSheet(
        report: ResearchAnalyticsReport
    ): ExportSheet {
        val headers = listOf(
            "Execution ID",
            "Order ID",
            "Signal ID",
            "Kind",
            "Quantity",
            "Price",
            "Gross P&L",
            "Charges",
            "Net P&L",
            "Executed at",
            "Event key",
            "Note"
        )
        val rows = report.dataset.allPaperExecutions.map { execution ->
            listOf(
                execution.id,
                execution.orderId,
                execution.signalId ?: AnalyticsTime.NOT_AVAILABLE,
                execution.kind.name,
                execution.quantity,
                execution.price,
                execution.grossPnl,
                execution.charges,
                execution.netPnl,
                time(execution.executedAt),
                execution.eventKey,
                execution.note.ifBlank {
                    AnalyticsTime.NOT_AVAILABLE
                }
            )
        }
        return ExportSheet("Paper Executions", headers, rows)
    }

    private fun pendingSheet(
        report: ResearchAnalyticsReport
    ): ExportSheet {
        val pending = report.dataset.rows.filter {
            !it.researchCompleted &&
                it.signal.status.name != "INVALIDATED"
        }
        return simpleSignalSheet("Pending Signals", pending)
    }

    private fun closedSheet(
        report: ResearchAnalyticsReport
    ): ExportSheet {
        val closed = report.dataset.rows.filter {
            it.researchCompleted
        }
        return simpleSignalSheet("Closed Signals", closed)
    }

    private fun simpleSignalSheet(
        name: String,
        rows: List<SignalAnalysisRow>
    ): ExportSheet {
        val headers = listOf(
            "Signal ID",
            "Symbol",
            "Exchange",
            "Category",
            "Direction",
            "Timeframe",
            "Strategy",
            "Status",
            "Generated at",
            "Triggered at",
            "Closed at",
            "Reference price",
            "Entry fill",
            "Exit price",
            "Stop loss",
            "Target 1",
            "Research state",
            "Research return %",
            "Research R",
            "Paper state",
            "Paper net P&L",
            "Reconciliation",
            "Snapshot source"
        )
        val data = rows.map { row ->
            val signal = row.signal
            listOf(
                signal.id,
                signal.symbol,
                signal.exchange,
                signal.category.label,
                signal.direction.label,
                signal.timeframe,
                signal.strategyId,
                signal.status.label,
                time(signal.generatedAt),
                time(signal.triggeredAt),
                time(signal.closedAt),
                signal.referencePrice,
                num(signal.entryFillPrice),
                num(signal.exitPrice),
                signal.stopLoss,
                signal.target1,
                row.researchState,
                num(row.researchReturnPct),
                num(row.researchR),
                row.paperState,
                num(row.paperNetPnl),
                row.reconciliationStatus,
                row.snapshotSource
            )
        }
        return ExportSheet(name, headers, data)
    }

    private fun metadataSheet(
        report: ResearchAnalyticsReport
    ): ExportSheet {
        val rows = listOf(
            listOf("Report", "Research & Analytics Center"),
            listOf("Generated at", time(report.dataset.generatedAt)),
            listOf("Data through", time(report.dataset.dataThrough)),
            listOf("Filter range", report.dataset.filter.rangeLabel()),
            listOf(
                "Categories",
                report.dataset.filter.categories.joinToString()
                    .ifBlank { "ALL" }
            ),
            listOf(
                "Directions",
                report.dataset.filter.directions.joinToString()
                    .ifBlank { "ALL" }
            ),
            listOf(
                "Strategies",
                report.dataset.filter.strategies.joinToString()
                    .ifBlank { "ALL" }
            ),
            listOf(
                "Symbols",
                report.dataset.filter.symbols.joinToString()
                    .ifBlank { "ALL" }
            ),
            listOf(
                "Timeframes",
                report.dataset.filter.timeframes.joinToString()
                    .ifBlank { "ALL" }
            ),
            listOf(
                "Minimum confidence",
                num(report.dataset.filter.minConfidence)
            ),
            listOf(
                "Paper filter",
                report.dataset.filter.paperFilter
            ),
            listOf(
                "Snapshot policy",
                "CAPTURED = exact at generation; " +
                    "RECONSTRUCTED_FROM_SIGNAL = copied from stored signal " +
                    "JSON; NOT_AVAILABLE = no recorded data."
            ),
            listOf(
                "Read-only policy",
                "Exports and analytics do not modify signals, paper " +
                    "orders, trades, balances or strategy configuration."
            ),
            listOf(
                "Reconciliation note",
                "Research and paper outcomes use different execution " +
                    "models. Pending paper positions remain pending."
            ),
            listOf(
                "Snapshot table available",
                bool(report.dataset.snapshotTableAvailable)
            )
        ) + listOfNotNull(
            report.dataset.snapshotTableError?.let {
                listOf("Snapshot table error", it)
            }
        )
        return ExportSheet(
            "Metadata",
            listOf("Key", "Value"),
            rows
        )
    }

    private fun summaryRow(
        prefix: List<Any?>,
        summary: PerformanceSummary,
        averageScore: Double? = null,
        averageConfidence: Double? = null,
        includeTotal: Boolean = false,
        includeIndicatorMetrics: Boolean = false
    ): List<Any?> {
        val values = ArrayList<Any?>()
        values.addAll(prefix)
        values.add(summary.total)
        values.add(summary.completed)
        values.add(summary.wins)
        values.add(summary.losses)
        values.add(summary.pending)
        values.add(num(summary.winRate))
        values.add(num(summary.averageReturnPct))
        values.add(num(summary.averageR))
        values.add(num(summary.profitFactor))
        if (includeIndicatorMetrics) {
            values.add(num(averageScore))
            values.add(num(averageConfidence))
        }
        if (includeTotal) {
            values.add(num(summary.totalPnl))
        }
        return values
    }

    private fun time(value: Long?): String =
        value?.let { AnalyticsTime.formatDateTime(it) }
            ?: AnalyticsTime.NOT_AVAILABLE

    private fun num(value: Double?): Any =
        if (value == null || !value.isFinite()) {
            AnalyticsTime.NOT_AVAILABLE
        } else {
            value
        }

    private fun bool(value: Boolean?): String = when (value) {
        true -> "YES"
        false -> "NO"
        null -> AnalyticsTime.NOT_AVAILABLE
    }

    private object JSON {
        fun of(values: Map<String, Any>): String =
            values.entries.joinToString(
                prefix = "{",
                postfix = "}"
            ) { (key, value) ->
                "\"${escape(key)}\":${
                    if (value is String) {
                        "\"${escape(value)}\""
                    } else {
                        value
                    }
                }"
            }

        private fun escape(value: String): String =
            value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
    }
}
