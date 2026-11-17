package com.sb.signals.services

import android.content.Context
import android.util.Log
import com.sb.backend.core.AppConfig
import com.sb.backend.core.BackendManager
import com.sb.backend.trading.PaperEntryDecisionType
import com.sb.backend.trading.PaperSignalCandidate
import com.sb.backend.trading.PaperSignalEligibility
import com.sb.backend.trading.PaperSignalForwardAction
import com.sb.backend.trading.PaperSignalForwardDecision
import com.sb.backend.trading.PaperSignalPolicy
import com.sb.backend.trading.PaperSignalQuote
import com.sb.backend.trading.PaperSignalSkipReason
import com.sb.backend.trading.PaperSizingRules
import com.sb.signals.domain.ResearchSignal
import com.sb.signals.domain.SignalLifecycleStatus

/**
 * Forwards genuine Research Signals into the paper ledger.
 *
 * NEW/ACTIVE signals become pending paper orders. TRIGGERED signals can fill
 * immediately only when a fresh quote is still inside the original entry
 * range. Completed and partially-completed signals are never replayed.
 */
object PaperSignalBridge {

    private const val TAG = "PaperSignalBridge"

    private val lock = Any()

    @Volatile
    var lastResult: Result? = null
        private set

    data class SkipDetail(
        val signalId: Long,
        val symbol: String,
        val reasonCode: PaperSignalSkipReason,
        val reason: String
    )

    data class Result(
        val scanned: Int,
        val eligible: Int,
        val forwarded: Int,
        val skipped: Int,
        val alreadyForwarded: Int,
        val expired: Int,
        val invalid: Int,
        val triggeredNotSimulatable: Int,
        val insufficientCapital: Int,
        val duplicates: Int,
        val errors: Int,
        val skipDetails: List<SkipDetail>,
        val message: String
    ) {
        fun summary(): String {
            return "scanned=$scanned, eligible=$eligible, forwarded=$forwarded, " +
                "skipped=$skipped, alreadyForwarded=$alreadyForwarded, " +
                "expired=$expired, invalid=$invalid, " +
                "triggeredNotSimulatable=$triggeredNotSimulatable, " +
                "insufficientCapital=$insufficientCapital, " +
                "duplicates=$duplicates, errors=$errors"
        }
    }

    fun forwardOpenSignals(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): Result = synchronized(lock) {
        if (!BackendManager.isInitialized() || !BackendManager.isReady()) {
            return record(failure(
                PaperSignalSkipReason.BACKEND_NOT_READY,
                "Paper backend is not ready."
            ))
        }

        val signalRepository = try {
            SignalsPlatform.repository(context)
        } catch (error: Exception) {
            Log.e(TAG, "Signals repository unavailable", error)
            return record(failure(
                PaperSignalSkipReason.SIGNALS_UNAVAILABLE,
                "Signals repository unavailable."
            ))
        }
        val paperRepository = try {
            BackendManager.paper()
        } catch (error: Exception) {
            Log.e(TAG, "Paper repository unavailable", error)
            return record(failure(
                PaperSignalSkipReason.PAPER_UNAVAILABLE,
                "Paper repository unavailable."
            ))
        }

        val signals = try {
            signalRepository.getOpenSignals()
        } catch (error: Exception) {
            Log.e(TAG, "Open signal query failed", error)
            return record(failure(
                PaperSignalSkipReason.QUERY_FAILED,
                "Open signal query failed."
            ))
        }
        val existingSignalIds = try {
            paperRepository.getSignalOrderIds().toMutableSet()
        } catch (error: Exception) {
            Log.e(TAG, "Existing paper signal query failed", error)
            return record(failure(
                PaperSignalSkipReason.QUERY_FAILED,
                "Existing paper signal query failed.",
                scanned = signals.size
            ))
        }

        var account = paperRepository.getAccount()
            ?: return record(failure(
                PaperSignalSkipReason.PAPER_UNAVAILABLE,
                "Paper account is not configured.",
                scanned = signals.size
            ))

        var eligible = 0
        var forwarded = 0
        var alreadyForwarded = 0
        var expired = 0
        var invalid = 0
        var triggeredNotSimulatable = 0
        var insufficientCapital = 0
        var duplicates = 0
        var errors = 0
        val skipDetails = ArrayList<SkipDetail>()

        fun skip(
            signal: ResearchSignal,
            reasonCode: PaperSignalSkipReason,
            reason: String
        ) {
            skipDetails.add(
                SkipDetail(
                    signalId = signal.id,
                    symbol = signal.symbol,
                    reasonCode = reasonCode,
                    reason = reason
                )
            )
            when (reasonCode) {
                PaperSignalSkipReason.SIGNAL_EXPIRED -> expired++
                PaperSignalSkipReason.INVALID_SIGNAL -> invalid++
                PaperSignalSkipReason.TRIGGERED_NOT_SIMULATABLE,
                PaperSignalSkipReason.PARTIAL_TARGET_ALREADY_HIT,
                PaperSignalSkipReason.OUTSIDE_ENTRY_RANGE,
                PaperSignalSkipReason.ENTRY_REJECTED -> {
                    triggeredNotSimulatable++
                }
                PaperSignalSkipReason.INSUFFICIENT_CAPITAL -> {
                    insufficientCapital++
                }
                PaperSignalSkipReason.DUPLICATE -> duplicates++
                else -> Unit
            }
        }

        signals.forEach { signal ->
            if (signal.id in existingSignalIds) {
                alreadyForwarded++
                skipDetails.add(
                    SkipDetail(
                        signalId = signal.id,
                        symbol = signal.symbol,
                        reasonCode = PaperSignalSkipReason.ALREADY_FORWARDED,
                        reason = "Signal already exists in the paper ledger."
                    )
                )
                return@forEach
            }

            val quote = if (
                signal.status == SignalLifecycleStatus.TRIGGERED
            ) {
                try {
                    BackendManager.market()
                        .getQuote(signal.symbol, signal.exchange)
                        ?.let { PaperSignalQuote(it.ltp, it.receivedAt) }
                } catch (error: Exception) {
                    Log.w(
                        TAG,
                        "Quote lookup failed for ${signal.symbol}",
                        error
                    )
                    null
                }
            } else {
                null
            }
            val decision = PaperSignalEligibility.decide(
                signal = signal,
                now = now,
                quote = quote,
                quoteMaxAgeMs = AppConfig.get().quoteMaxAgeMs
            )
            if (decision.action == PaperSignalForwardAction.SKIP) {
                skip(
                    signal,
                    decision.reasonCode ?: PaperSignalSkipReason.INVALID_SIGNAL,
                    decision.reason
                )
                return@forEach
            }
            val candidate = candidateFor(signal)
            if (candidate == null) {
                invalid++
                skipDetails.add(
                    SkipDetail(
                        signalId = signal.id,
                        symbol = signal.symbol,
                        reasonCode = PaperSignalSkipReason.INVALID_SIGNAL,
                        reason = "Signal levels cannot be forwarded."
                    )
                )
                return@forEach
            }
            eligible++

            try {
                val result = when (decision.action) {
                    PaperSignalForwardAction.CREATE_PENDING -> {
                        forwardPending(
                            candidate = candidate,
                            accountAvailable = account.availableBalance,
                            repository = paperRepository
                        )
                    }
                    PaperSignalForwardAction.FILL_NOW -> {
                        forwardTriggered(
                            candidate = candidate,
                            decision = decision,
                            accountAvailable = account.availableBalance,
                            repository = paperRepository,
                            now = now
                        )
                    }
                    PaperSignalForwardAction.SKIP -> null
                }
                if (result == null) {
                    skip(
                        signal,
                        PaperSignalSkipReason.ENTRY_REJECTED,
                        "Paper entry was rejected by the simulation policy."
                    )
                    return@forEach
                }
                if (!result.inserted) {
                    duplicates++
                    skipDetails.add(
                        SkipDetail(
                            signalId = signal.id,
                            symbol = signal.symbol,
                            reasonCode = PaperSignalSkipReason.DUPLICATE,
                            reason = "Paper order was inserted by another cycle."
                        )
                    )
                    return@forEach
                }

                forwarded++
                existingSignalIds.add(signal.id)
                account = paperRepository.getAccount() ?: account
            } catch (error: InsufficientPaperCapitalException) {
                insufficientCapital++
                skipDetails.add(
                    SkipDetail(
                        signalId = signal.id,
                        symbol = signal.symbol,
                        reasonCode = PaperSignalSkipReason.INSUFFICIENT_CAPITAL,
                        reason = error.message ?: "Insufficient paper capital."
                    )
                )
            } catch (error: Exception) {
                errors++
                skipDetails.add(
                    SkipDetail(
                        signalId = signal.id,
                        symbol = signal.symbol,
                        reasonCode = PaperSignalSkipReason.SAVE_FAILED,
                        reason = error.message ?: "Paper forwarding failed."
                    )
                )
                Log.w(
                    TAG,
                    "Could not forward signal ${signal.id} " +
                        "(${signal.symbol} ${signal.category.name}).",
                    error
                )
            }
        }

        val skipped = signals.size - forwarded
        val message = if (forwarded > 0) {
            "Forwarded $forwarded signal(s) to paper trading."
        } else {
            "No new eligible signals to forward."
        }
        record(Result(
            scanned = signals.size,
            eligible = eligible,
            forwarded = forwarded,
            skipped = skipped,
            alreadyForwarded = alreadyForwarded,
            expired = expired,
            invalid = invalid,
            triggeredNotSimulatable = triggeredNotSimulatable,
            insufficientCapital = insufficientCapital,
            duplicates = duplicates,
            errors = errors,
            skipDetails = skipDetails,
            message = message
        ))
    }

    private fun forwardPending(
        candidate: PaperSignalCandidate,
        accountAvailable: Double,
        repository: com.sb.backend.repositories.PaperRepository
    ): com.sb.backend.repositories.SignalOrderSaveResult? {
        val expectedEntry = PaperSignalPolicy.expectedEntryPrice(
            candidate = candidate,
            applySlippage = true
        )
        if (expectedEntry <= 0.0) return null
        val quantity = PaperSignalPolicy.initialQuantity(
            candidate = candidate,
            availableBalance = accountAvailable,
            rules = sizingRules(candidate.costBpsPerSide),
            expectedEntryPrice = expectedEntry
        )
        if (quantity <= 0) {
            throw InsufficientPaperCapitalException(
                "Insufficient paper capital at the expected entry price."
            )
        }
        return repository.saveSignalOrderDetailed(candidate, quantity)
    }

    private fun forwardTriggered(
        candidate: PaperSignalCandidate,
        decision: PaperSignalForwardDecision,
        accountAvailable: Double,
        repository: com.sb.backend.repositories.PaperRepository,
        now: Long
    ): com.sb.backend.repositories.SignalOrderSaveResult? {
        val quote = decision.quote ?: return null
        val entry = PaperSignalPolicy.entryDecision(
            candidate = candidate,
            quotePrice = quote.price,
            quoteFresh = true,
            now = now
        )
        if (entry.type != PaperEntryDecisionType.FILL ||
            entry.price == null) {
            return null
        }
        val executionPrice = entry.price
        val quantity = PaperSignalPolicy.initialQuantity(
            candidate = candidate,
            availableBalance = accountAvailable,
            rules = sizingRules(candidate.costBpsPerSide),
            expectedEntryPrice = executionPrice
        )
        if (quantity <= 0) {
            throw InsufficientPaperCapitalException(
                "Insufficient paper capital at the triggered entry price."
            )
        }
        return repository.saveAndFillSignalOrder(
            candidate = candidate,
            quantity = quantity,
            executionPrice = executionPrice,
            executedAt = now
        )
    }

    private fun candidateFor(
        signal: ResearchSignal
    ): PaperSignalCandidate? {
        val levels = signal.entryLow.isFinite() &&
            signal.entryHigh.isFinite() &&
            signal.stopLoss.isFinite() &&
            signal.target1.isFinite() &&
            signal.target2.isFinite() &&
            signal.target3.isFinite() &&
            signal.referencePrice.isFinite()
        if (!levels) return null

        val paperConfig = AppConfig.get()
        return PaperSignalCandidate(
            signalId = signal.id,
            symbol = signal.symbol,
            exchange = signal.exchange,
            side = signal.direction.name,
            category = signal.category.name,
            timeframe = signal.timeframe,
            strategyId = signal.strategyId,
            strategyVersion = signal.strategyVersion,
            referencePrice = signal.referencePrice,
            entryLow = signal.entryLow,
            entryHigh = signal.entryHigh,
            stopLoss = signal.stopLoss,
            target1 = signal.target1,
            target2 = signal.target2,
            target3 = signal.target3,
            generatedAt = signal.generatedAt,
            expiresAt = signal.expiresAt,
            costBpsPerSide = paperConfig.paperCostBpsPerSide,
            slippageBpsPerSide = paperConfig.paperSlippageBpsPerSide
        )
    }

    private fun sizingRules(costBpsPerSide: Double): PaperSizingRules {
        val config = AppConfig.get()
        return PaperSizingRules(
            riskPerTradePercent = config.paperRiskPerTradePercent,
            allocationPercent = config.paperAllocationPercent,
            shortMarginRate = config.shortMarginRate,
            maxQuantity = config.maxOrderQuantity,
            costBpsPerSide = costBpsPerSide
        )
    }

    private fun failure(
        reasonCode: PaperSignalSkipReason,
        message: String,
        scanned: Int = 0
    ): Result {
        return Result(
            scanned = scanned,
            eligible = 0,
            forwarded = 0,
            skipped = scanned,
            alreadyForwarded = 0,
            expired = 0,
            invalid = 0,
            triggeredNotSimulatable = 0,
            insufficientCapital = 0,
            duplicates = 0,
            errors = 1,
            skipDetails = listOf(
                SkipDetail(
                    signalId = 0L,
                    symbol = "",
                    reasonCode = reasonCode,
                    reason = message
                )
            ),
            message = message
        )
    }

    private fun record(result: Result): Result {
        lastResult = result
        return result
    }

    private class InsufficientPaperCapitalException(
        message: String
    ) : IllegalStateException(message)
}
