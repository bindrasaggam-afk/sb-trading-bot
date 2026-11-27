package com.sb.researchanalytics.domain

import com.sb.backend.models.PaperExecution
import com.sb.backend.models.PaperOrder
import com.sb.backend.repositories.PaperAccount
import com.sb.signals.domain.ResearchSignal
import com.sb.signals.domain.SignalCategory
import com.sb.signals.domain.SignalDirection
import com.sb.signals.domain.SignalEvent
import com.sb.signals.domain.SignalLifecycleStatus

data class AnalyticsFilter(
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
    val categories: Set<SignalCategory> = emptySet(),
    val directions: Set<SignalDirection> = emptySet(),
    val strategies: Set<String> = emptySet(),
    val symbols: Set<String> = emptySet(),
    val statuses: Set<SignalLifecycleStatus> = emptySet(),
    val timeframes: Set<String> = emptySet(),
    val minConfidence: Double? = null,
    val paperFilter: String = ALL
) {
    fun matches(signal: ResearchSignal): Boolean {
        if (fromMillis != null && signal.generatedAt < fromMillis) {
            return false
        }
        if (toMillis != null && signal.generatedAt > toMillis) {
            return false
        }
        if (categories.isNotEmpty() &&
            signal.category !in categories
        ) {
            return false
        }
        if (directions.isNotEmpty() &&
            signal.direction !in directions
        ) {
            return false
        }
        if (strategies.isNotEmpty() &&
            signal.strategyId !in strategies
        ) {
            return false
        }
        if (symbols.isNotEmpty() &&
            signal.symbol.uppercase() !in
            symbols.map { it.uppercase() }.toSet()
        ) {
            return false
        }
        if (statuses.isNotEmpty() && signal.status !in statuses) {
            return false
        }
        if (timeframes.isNotEmpty() &&
            signal.timeframe !in timeframes
        ) {
            return false
        }
        if (minConfidence != null &&
            signal.confidence < minConfidence
        ) {
            return false
        }
        return true
    }

    fun rangeLabel(): String {
        val from = fromMillis?.let { AnalyticsTime.formatDate(it) }
            ?: "Beginning"
        val to = toMillis?.let { AnalyticsTime.formatDate(it) }
            ?: "Now"
        return "$from to $to"
    }

    companion object {
        const val ALL = "ALL"
        const val PAPER_LINKED = "PAPER_LINKED"
        const val PAPER_PENDING = "PAPER_PENDING"
        const val PAPER_CLOSED = "PAPER_CLOSED"
        const val RESEARCH_ONLY = "RESEARCH_ONLY"
    }
}

data class SignalAnalysisRow(
    val signal: ResearchSignal,
    val snapshot: SnapshotPair,
    val paperOrders: List<PaperOrder>,
    val events: List<SignalEvent>,
    val paperExecutions: List<PaperExecution> = emptyList()
) {
    val researchCompleted: Boolean
        get() = signal.status.isCompleted &&
            signal.status != SignalLifecycleStatus.INVALIDATED

    val researchWin: Boolean?
        get() {
            if (!researchCompleted) return null
            if (signal.status == SignalLifecycleStatus.STOPLOSS_HIT) {
                return false
            }
            if (signal.status == SignalLifecycleStatus.TARGET_3_HIT) {
                return true
            }
            val pnl = researchReturnPct ?: return null
            return pnl > 0.0
        }

    val researchReturnPct: Double?
        get() = signal.outcomeNetPnlPercent
            ?: signal.outcomePnlPercent

    val researchR: Double?
        get() = signal.outcomeR

    val researchState: String
        get() = when {
            signal.status == SignalLifecycleStatus.INVALIDATED ->
                "Invalidated"
            signal.status.isCompleted -> when (researchWin) {
                true -> "Win"
                false -> "Loss"
                null -> signal.status.label
            }
            else -> signal.status.label
        }

    val closedPaperOrders: List<PaperOrder>
        get() = paperOrders.filter {
            it.status == com.sb.backend.models.OrderStatus.CLOSED.name
        }

    val paperClosed: Boolean
        get() = closedPaperOrders.isNotEmpty()

    val paperPending: Boolean
        get() = paperOrders.any {
            it.status == com.sb.backend.models.OrderStatus.PENDING.name
        }

    val paperOpen: Boolean
        get() = paperOrders.any {
            it.status == com.sb.backend.models.OrderStatus.OPEN.name
        }

    val paperNetPnl: Double?
        get() {
            if (!paperClosed) return null
            val realized = closedPaperOrders.sumOf {
                it.realizedPnl ?: 0.0
            }
            return realized
        }

    val paperWin: Boolean?
        get() {
            if (!paperClosed) return null
            val pnl = paperNetPnl ?: return null
            return pnl > 0.0
        }

    val paperState: String
        get() = when {
            paperClosed -> "Closed"
            paperOpen -> "Open"
            paperPending -> "Pending"
            else -> "Pending (No paper order)"
        }

    val paperReturnsPending: Boolean
        get() = !paperClosed

    val reconciliationStatus: String
        get() = when {
            paperOrders.isEmpty() && researchCompleted ->
                "Research only"
            paperOrders.isEmpty() -> "Paper pending"
            paperClosed && researchCompleted -> {
                val research = researchWin
                val paper = paperWin
                if (research == null || paper == null) {
                    "Review"
                } else if (research == paper) {
                    "Agreement"
                } else {
                    "Outcome mismatch"
                }
            }
            paperClosed && !researchCompleted -> "Paper closed / research open"
            researchCompleted && !paperClosed -> "Research closed / paper open"
            else -> "In progress"
        }

    val snapshotSource: String
        get() = snapshot.indicator.source

    val paperExecutionValue: Double
        get() = paperExecutions.sumOf { it.netPnl }

    val paperCharges: Double
        get() = paperExecutions.sumOf { it.charges }

    val paperGrossPnl: Double
        get() = paperExecutions.sumOf { it.grossPnl }
}

data class PerformanceSummary(
    val total: Int = 0,
    val completed: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val pending: Int = 0,
    val invalidated: Int = 0,
    val winRate: Double? = null,
    val averageReturnPct: Double? = null,
    val averageR: Double? = null,
    val totalPnl: Double = 0.0,
    val profitFactor: Double? = null,
    val expectancyPct: Double? = null,
    val maxDrawdownPct: Double? = null,
    val averageHoldingHours: Double? = null
)

data class GroupPerformance(
    val key: String,
    val label: String,
    val summary: PerformanceSummary
)

data class IndicatorStateStat(
    val indicator: String,
    val confirmed: Boolean,
    val summary: PerformanceSummary,
    val averageScore: Double? = null,
    val averageConfidence: Double? = null
)

data class IndicatorScoreBucketStat(
    val indicator: String,
    val bucket: String,
    val summary: PerformanceSummary
)

data class CombinationStat(
    val combination: String,
    val confirmedIndicators: Int,
    val summary: PerformanceSummary
)

data class MarketConditionStat(
    val dimension: String,
    val bucket: String,
    val summary: PerformanceSummary
)

data class EntryTimingStat(
    val key: String,
    val label: String,
    val sample: Int,
    val average: Double?
)

data class DataQualityIssue(
    val severity: String,
    val category: String,
    val message: String,
    val affectedSignals: Int
) {
    companion object {
        const val INFO = "INFO"
        const val WARNING = "WARNING"
        const val ERROR = "ERROR"
    }
}

data class DataQualitySummary(
    val totalSignals: Int = 0,
    val capturedSnapshots: Int = 0,
    val reconstructedSnapshots: Int = 0,
    val unavailableSnapshots: Int = 0,
    val incompleteSnapshots: Int = 0,
    val missingIndicatorValues: Int = 0,
    val missingScores: Int = 0,
    val duplicateSignalKeys: Int = 0,
    val ambiguousBars: Int = 0,
    val averageDataQualityScore: Double? = null,
    val issues: List<DataQualityIssue> = emptyList()
)

data class ReconciliationSummary(
    val researchSignals: Int = 0,
    val paperOrders: Int = 0,
    val linkedSignals: Int = 0,
    val researchWithoutPaper: Int = 0,
    val paperPendingWithoutExecution: Int = 0,
    val paperClosedWithResearchOpen: Int = 0,
    val researchClosedWithoutPaperClose: Int = 0,
    val orphanPaperOrders: Int = 0,
    val outcomeAgreements: Int = 0,
    val outcomeMismatches: Int = 0,
    val pnlMismatches: Int = 0,
    val researchOnlyPnlPct: Double? = null,
    val paperRealizedPnl: Double = 0.0,
    val notes: List<String> = emptyList()
)

data class SignalOutcomeExportRow(
    val row: SignalAnalysisRow,
    val reasons: String
)

data class AnalyticsDataset(
    val generatedAt: Long,
    val dataThrough: Long,
    val filter: AnalyticsFilter,
    val signals: List<ResearchSignal>,
    val rows: List<SignalAnalysisRow>,
    val allSignalIds: Set<Long>,
    val allPaperOrders: List<PaperOrder>,
    val allPaperExecutions: List<PaperExecution>,
    val paperAccount: PaperAccount?,
    val snapshotTableAvailable: Boolean,
    val snapshotTableError: String? = null
)

data class ResearchAnalyticsReport(
    val dataset: AnalyticsDataset,
    val research: PerformanceSummary,
    val paper: PerformanceSummary,
    val indicators: List<IndicatorStateStat>,
    val scoreBuckets: List<IndicatorScoreBucketStat>,
    val combinations: List<CombinationStat>,
    val strategies: List<GroupPerformance>,
    val categories: List<GroupPerformance>,
    val directions: List<GroupPerformance>,
    val timeframes: List<GroupPerformance>,
    val symbols: List<GroupPerformance>,
    val holdingPeriods: List<GroupPerformance>,
    val marketConditions: List<MarketConditionStat>,
    val entryTiming: List<EntryTimingStat>,
    val exits: List<GroupPerformance>,
    val dataQuality: DataQualitySummary,
    val reconciliation: ReconciliationSummary,
    val sheets: List<ExportSheet>
)

data class ExportSheet(
    val name: String,
    val headers: List<String>,
    val rows: List<List<Any?>>
) {
    val rowCount: Int
        get() = rows.size
}

data class ExportHistoryEntry(
    val id: Long = 0L,
    val exportType: String,
    val format: String,
    val fileName: String,
    val filtersJson: String,
    val rowCount: Int,
    val status: String,
    val message: String,
    val createdAt: Long
)
