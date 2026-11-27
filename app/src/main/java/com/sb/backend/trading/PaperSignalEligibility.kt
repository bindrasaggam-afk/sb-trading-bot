package com.sb.backend.trading

import com.sb.signals.domain.ResearchSignal
import com.sb.signals.domain.SignalLifecycleStatus
import java.util.Locale

data class PaperSignalQuote(
    val price: Double,
    val receivedAt: Long
) {
    val isValid: Boolean
        get() = price.isFinite() && price > 0.0 && receivedAt > 0L
}

enum class PaperSignalForwardAction {
    CREATE_PENDING,
    FILL_NOW,
    SKIP
}

enum class PaperSignalSkipReason {
    ALREADY_FORWARDED,
    SIGNAL_COMPLETED,
    SIGNAL_EXPIRED,
    INVALID_SIGNAL,
    MISSING_QUOTE,
    STALE_QUOTE,
    OUTSIDE_ENTRY_RANGE,
    TRIGGERED_NOT_SIMULATABLE,
    PARTIAL_TARGET_ALREADY_HIT,
    INSUFFICIENT_CAPITAL,
    DUPLICATE,
    SAVE_FAILED,
    ENTRY_REJECTED,
    BACKEND_NOT_READY,
    SIGNALS_UNAVAILABLE,
    PAPER_UNAVAILABLE,
    QUERY_FAILED,
    UNSUPPORTED_TIMEFRAME
}

data class PaperSignalForwardDecision(
    val action: PaperSignalForwardAction,
    val reasonCode: PaperSignalSkipReason? = null,
    val reason: String = "",
    val quote: PaperSignalQuote? = null
)

object PaperSignalEligibility {

    fun decide(
        signal: ResearchSignal,
        now: Long,
        quote: PaperSignalQuote?,
        quoteMaxAgeMs: Long
    ): PaperSignalForwardDecision {
        if (signal.id <= 0L ||
            signal.symbol.isBlank() ||
            signal.exchange.isBlank() ||
            signal.generatedAt <= 0L ||
            signal.expiresAt <= 0L ||
            !levelsValid(signal)
        ) {
            return skip(
                PaperSignalSkipReason.INVALID_SIGNAL,
                "Signal metadata or price levels are invalid."
            )
        }

        if (signal.category.name == "INTRADAY" &&
            !isSupportedIntradayTimeframe(signal.timeframe)
        ) {
            return skip(
                PaperSignalSkipReason.UNSUPPORTED_TIMEFRAME,
                "Intraday paper trading requires a timeframe of at least 5 minutes."
            )
        }

        if (now >= signal.expiresAt) {
            return skip(
                PaperSignalSkipReason.SIGNAL_EXPIRED,
                "Signal expired before paper forwarding."
            )
        }

        return when (signal.status) {
            SignalLifecycleStatus.NEW,
            SignalLifecycleStatus.ACTIVE -> {
                PaperSignalForwardDecision(
                    action = PaperSignalForwardAction.CREATE_PENDING,
                    reason = "Waiting for a valid entry quote."
                )
            }
            SignalLifecycleStatus.TRIGGERED -> {
                decideTriggered(signal, now, quote, quoteMaxAgeMs)
            }
            SignalLifecycleStatus.TARGET_1_HIT,
            SignalLifecycleStatus.TARGET_2_HIT -> {
                skip(
                    PaperSignalSkipReason.PARTIAL_TARGET_ALREADY_HIT,
                    "Signal already progressed beyond its initial entry."
                )
            }
            SignalLifecycleStatus.TARGET_3_HIT,
            SignalLifecycleStatus.STOPLOSS_HIT,
            SignalLifecycleStatus.EXPIRED,
            SignalLifecycleStatus.INVALIDATED,
            SignalLifecycleStatus.CLOSED -> {
                skip(
                    PaperSignalSkipReason.SIGNAL_COMPLETED,
                    "Signal lifecycle is already complete."
                )
            }
        }
    }

    private fun decideTriggered(
        signal: ResearchSignal,
        now: Long,
        quote: PaperSignalQuote?,
        quoteMaxAgeMs: Long
    ): PaperSignalForwardDecision {
        if (quote == null || !quote.isValid) {
            return skip(
                PaperSignalSkipReason.MISSING_QUOTE,
                "Triggered signal has no valid market quote."
            )
        }
        val maxAge = quoteMaxAgeMs.coerceAtLeast(0L)
        if (now - quote.receivedAt > maxAge || quote.receivedAt > now) {
            return skip(
                PaperSignalSkipReason.STALE_QUOTE,
                "Triggered signal quote is stale."
            )
        }

        val low = minOf(signal.entryLow, signal.entryHigh)
        val high = maxOf(signal.entryLow, signal.entryHigh)
        if (quote.price < low || quote.price > high) {
            return skip(
                PaperSignalSkipReason.OUTSIDE_ENTRY_RANGE,
                "Triggered quote is outside the original entry range."
            )
        }

        val stopTouched = if (signal.direction.name == "BUY") {
            quote.price <= signal.stopLoss
        } else {
            quote.price >= signal.stopLoss
        }
        val targetTouched = if (signal.direction.name == "BUY") {
            quote.price >= signal.target1
        } else {
            quote.price <= signal.target1
        }
        if (stopTouched || targetTouched) {
            return skip(
                PaperSignalSkipReason.TRIGGERED_NOT_SIMULATABLE,
                "Triggered quote has already crossed the stop or first target."
            )
        }

        return PaperSignalForwardDecision(
            action = PaperSignalForwardAction.FILL_NOW,
            reason = "Triggered signal can be filled from the current quote.",
            quote = quote
        )
    }

    private fun isSupportedIntradayTimeframe(timeframe: String): Boolean {
        val normalized = timeframe.trim().uppercase(Locale.ROOT).replace(" ", "")
        val match = Regex(
            """^(?:(\d+)(M|MIN|MINS|MINUTE|MINUTES|H|HR|HRS|HOUR|HOURS)|MIN_(\d+))$"""
        ).matchEntire(normalized) ?: return false
        val unit = match.groupValues[2]
        val amount = (match.groupValues[1].ifBlank {
            match.groupValues[3]
        }).toIntOrNull() ?: return false
        val minutes = if (unit.startsWith("H")) amount * 60L else amount.toLong()
        return minutes >= 5L
    }

    private fun levelsValid(signal: ResearchSignal): Boolean {
        if (!signal.referencePrice.isFinite() ||
            signal.referencePrice <= 0.0 ||
            !signal.entryLow.isFinite() ||
            !signal.entryHigh.isFinite() ||
            !signal.stopLoss.isFinite() ||
            !signal.target1.isFinite() ||
            !signal.target2.isFinite() ||
            !signal.target3.isFinite()
        ) {
            return false
        }
        val low = minOf(signal.entryLow, signal.entryHigh)
        val high = maxOf(signal.entryLow, signal.entryHigh)
        if (low <= 0.0 || high <= 0.0 || signal.stopLoss <= 0.0) {
            return false
        }
        if (signal.target1 <= 0.0 ||
            signal.target2 <= 0.0 ||
            signal.target3 <= 0.0
        ) {
            return false
        }
        return if (signal.direction.name == "BUY") {
            signal.stopLoss < low &&
                signal.target1 > high &&
                signal.target2 > signal.target1 &&
                signal.target3 > signal.target2
        } else {
            signal.stopLoss > high &&
                signal.target1 < low &&
                signal.target2 < signal.target1 &&
                signal.target3 < signal.target2
        }
    }

    private fun skip(
        reasonCode: PaperSignalSkipReason,
        reason: String
    ): PaperSignalForwardDecision = PaperSignalForwardDecision(
        action = PaperSignalForwardAction.SKIP,
        reasonCode = reasonCode,
        reason = reason
    )
}
