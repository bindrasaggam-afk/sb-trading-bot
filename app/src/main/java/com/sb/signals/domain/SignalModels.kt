package com.sb.signals.domain

import java.util.Locale

enum class SignalCategory(val label: String) {
    INTRADAY("Intraday"),
    SWING("Swing"),
    DELIVERY("Delivery");

    companion object {
        fun fromName(value: String): SignalCategory? =
            values().firstOrNull { it.name == value.uppercase(Locale.ROOT) }
    }
}

enum class SignalDirection(val label: String) {
    BUY("BUY"),
    SELL("SELL");

    companion object {
        fun fromName(value: String): SignalDirection? =
            values().firstOrNull { it.name == value.uppercase(Locale.ROOT) }
    }
}

enum class SignalLifecycleStatus(val label: String) {
    NEW("New"),
    ACTIVE("Active"),
    TRIGGERED("Triggered"),
    TARGET_1_HIT("Target 1 hit"),
    TARGET_2_HIT("Target 2 hit"),
    TARGET_3_HIT("Target 3 hit"),
    STOPLOSS_HIT("Stop-loss hit"),
    EXPIRED("Expired"),
    INVALIDATED("Invalidated"),
    CLOSED("Closed");

    val isOpen: Boolean
        get() = this == NEW ||
            this == ACTIVE ||
            this == TRIGGERED ||
            this == TARGET_1_HIT ||
            this == TARGET_2_HIT

    val isCompleted: Boolean
        get() = this == TARGET_3_HIT ||
            this == STOPLOSS_HIT ||
            this == EXPIRED ||
            this == INVALIDATED ||
            this == CLOSED

    companion object {
        fun fromName(value: String): SignalLifecycleStatus? =
            values().firstOrNull { it.name == value.uppercase(Locale.ROOT) }
    }
}

enum class HoldingPeriod(
    val label: String,
    val minDays: Int,
    val maxDays: Int,
    val filterGroup: HoldingFilterGroup
) {
    ONE_DAY("1 day", 1, 1, HoldingFilterGroup.DAYS),
    TWO_DAYS("2 days", 2, 2, HoldingFilterGroup.DAYS),
    THREE_DAYS("3 days", 3, 3, HoldingFilterGroup.DAYS),
    ONE_WEEK("1 week", 5, 7, HoldingFilterGroup.WEEKS),
    TWO_WEEKS("2 weeks", 8, 14, HoldingFilterGroup.WEEKS),
    THREE_WEEKS("3 weeks", 15, 21, HoldingFilterGroup.WEEKS),
    FOUR_WEEKS("4 weeks", 22, 28, HoldingFilterGroup.WEEKS),
    ONE_MONTH("1 month", 29, 45, HoldingFilterGroup.MONTHS),
    TWO_MONTHS("2 months", 46, 75, HoldingFilterGroup.MONTHS),
    THREE_MONTHS("3 months", 76, 120, HoldingFilterGroup.MONTHS),
    FOUR_MONTHS("4 months", 121, 150, HoldingFilterGroup.MONTHS),
    FIVE_MONTHS("5 months", 151, 180, HoldingFilterGroup.MONTHS),
    SIX_MONTHS("6 months", 181, 220, HoldingFilterGroup.MONTHS);

    companion object {
        fun fromName(value: String): HoldingPeriod? =
            values().firstOrNull { it.name == value.uppercase(Locale.ROOT) }
    }
}

enum class HoldingFilterGroup(val label: String) {
    ALL("All"),
    DAYS("1-3 days"),
    WEEKS("1-4 weeks"),
    MONTHS("1-6 months")
}

data class SignalLevels(
    val entryLow: Double,
    val entryHigh: Double,
    val stopLoss: Double,
    val target1: Double,
    val target2: Double,
    val target3: Double
)

data class SignalIndicatorSnapshot(
    val values: Map<String, Double?> = emptyMap(),
    val confirmations: Map<String, Boolean> = emptyMap(),
    val parameters: Map<String, Double?> = emptyMap()
)

data class DataQuality(
    val score: Double,
    val candleCount: Int,
    val firstTimestamp: Long?,
    val lastTimestamp: Long?,
    val notes: List<String> = emptyList()
) {
    val isUsable: Boolean
        get() = score >= 70.0 && candleCount > 0 && lastTimestamp != null
}

data class StrategyCandidate(
    val direction: SignalDirection,
    val score: Double,
    val bullScore: Double,
    val bearScore: Double,
    val reasons: List<String>,
    val snapshot: SignalIndicatorSnapshot,
    val holdingPeriod: HoldingPeriod
)

data class ResearchSignal(
    val id: Long = 0L,
    val symbol: String,
    val exchange: String,
    val category: SignalCategory,
    val direction: SignalDirection,
    val timeframe: String,
    val strategyId: String,
    val strategyVersion: String,
    val strategyConfigJson: String = "",
    val candleTimestamp: Long,
    val generatedAt: Long,
    val referencePrice: Double,
    val entryLow: Double,
    val entryHigh: Double,
    val stopLoss: Double,
    val target1: Double,
    val target2: Double,
    val target3: Double,
    val riskPerUnit: Double,
    val rewardRisk1: Double,
    val rewardRisk2: Double,
    val rewardRisk3: Double,
    val confidence: Double,
    val holdingPeriod: HoldingPeriod,
    val expiresAt: Long,
    val status: SignalLifecycleStatus = SignalLifecycleStatus.NEW,
    val reasons: List<String> = emptyList(),
    val indicatorSnapshot: SignalIndicatorSnapshot = SignalIndicatorSnapshot(),
    val dataQuality: DataQuality,
    val triggeredAt: Long? = null,
    val target1At: Long? = null,
    val target2At: Long? = null,
    val target3At: Long? = null,
    val stoppedAt: Long? = null,
    val closedAt: Long? = null,
    val entryFillPrice: Double? = null,
    val exitPrice: Double? = null,
    val outcomeR: Double? = null,
    val outcomePnlPercent: Double? = null,
    val outcomeNetPnlPercent: Double? = null,
    val outcomeCostPercent: Double? = null,
    val outcomeReason: String? = null,
    val ambiguousBar: Boolean = false,
    val maxFavorableExcursionPercent: Double? = null,
    val maxAdverseExcursionPercent: Double? = null,
    val lastEvaluatedAt: Long? = null,
    val evaluationVersion: String = "2.0"
) {
    val dedupeKey: String
        get() = listOf(
            exchange.uppercase(Locale.ROOT),
            symbol.uppercase(Locale.ROOT),
            category.name,
            timeframe,
            strategyId,
            strategyVersion,
            candleTimestamp.toString()
        ).joinToString("|")
}

data class SignalEvent(
    val id: Long = 0L,
    val signalId: Long,
    val fromStatus: SignalLifecycleStatus?,
    val toStatus: SignalLifecycleStatus,
    val eventTime: Long,
    val price: Double? = null,
    val message: String = ""
)

data class SignalStrategyVersion(
    val strategyId: String,
    val version: String,
    val category: SignalCategory,
    val name: String,
    val configJson: String,
    val createdAt: Long,
    val active: Boolean = true,
    val notes: String = ""
)

data class SignalFilter(
    val category: SignalCategory? = null,
    val statuses: Set<SignalLifecycleStatus> = emptySet(),
    val direction: SignalDirection? = null,
    val holdingGroup: HoldingFilterGroup? = null,
    val symbolQuery: String = "",
    val fromTimestamp: Long? = null,
    val toTimestamp: Long? = null,
    val limit: Int = 200
)

enum class TradeOutcome {
    WIN,
    LOSS,
    BREAKEVEN
}

data class SignalAnalysisRun(
    val id: Long = 0L,
    val startedAt: Long,
    val finishedAt: Long,
    val categories: String,
    val symbolsEvaluated: Int,
    val signalsGenerated: Int,
    val signalsEvaluated: Int,
    val errors: Int,
    val message: String
)

data class OutcomeResult(
    val status: SignalLifecycleStatus,
    val eventTime: Long,
    val exitPrice: Double?,
    val eventPrice: Double? = null,
    val outcomeR: Double?,
    val outcomePnlPercent: Double?,
    val outcomeNetPnlPercent: Double?,
    val outcomeCostPercent: Double?,
    val reason: String,
    val fillPrice: Double? = null,
    val triggeredAt: Long? = null,
    val target1At: Long? = null,
    val target2At: Long? = null,
    val target3At: Long? = null,
    val stoppedAt: Long? = null,
    val closedAt: Long? = null,
    val ambiguousBar: Boolean = false,
    val mfePercent: Double? = null,
    val maePercent: Double? = null
) {
    val isCompleted: Boolean
        get() = status.isCompleted
}
