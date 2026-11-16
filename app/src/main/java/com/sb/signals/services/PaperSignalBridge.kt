package com.sb.signals.services

import android.content.Context
import android.util.Log
import com.sb.backend.core.AppConfig
import com.sb.backend.core.BackendManager
import com.sb.backend.trading.PaperSignalCandidate
import com.sb.backend.trading.PaperSignalPolicy
import com.sb.backend.trading.PaperSizingRules
import com.sb.signals.data.SignalJson
import com.sb.signals.domain.ResearchSignal
import com.sb.signals.domain.SignalLifecycleStatus
import com.sb.signals.domain.StrategyConfig
import com.sb.signals.domain.StrategyDefaults

/**
 * Forwards genuine Research Signals into the paper ledger.
 *
 * Only open, unexpired NEW/ACTIVE signals are eligible. Signal IDs are unique
 * in the paper ledger, so repeated analysis and monitor cycles are idempotent.
 */
object PaperSignalBridge {

    private const val TAG = "PaperSignalBridge"

    private val lock = Any()
    private val eligibleStatuses = setOf(
        SignalLifecycleStatus.NEW,
        SignalLifecycleStatus.ACTIVE
    )

    data class Result(
        val scanned: Int,
        val forwarded: Int,
        val skipped: Int,
        val message: String
    )

    fun forwardOpenSignals(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): Result = synchronized(lock) {
        if (!BackendManager.isInitialized() || !BackendManager.isReady()) {
            return Result(
                scanned = 0,
                forwarded = 0,
                skipped = 0,
                message = "Paper backend is not ready."
            )
        }

        val signalRepository = try {
            SignalsPlatform.repository(context)
        } catch (error: Exception) {
            Log.e(TAG, "Signals repository unavailable", error)
            return Result(
                scanned = 0,
                forwarded = 0,
                skipped = 0,
                message = "Signals repository unavailable."
            )
        }
        val paperRepository = try {
            BackendManager.paper()
        } catch (error: Exception) {
            Log.e(TAG, "Paper repository unavailable", error)
            return Result(
                scanned = 0,
                forwarded = 0,
                skipped = 0,
                message = "Paper repository unavailable."
            )
        }

        val signals = try {
            signalRepository.getOpenSignals()
        } catch (error: Exception) {
            Log.e(TAG, "Open signal query failed", error)
            return Result(
                scanned = 0,
                forwarded = 0,
                skipped = 0,
                message = "Open signal query failed."
            )
        }
        val existingSignalIds = try {
            paperRepository.getSignalOrderIds().toMutableSet()
        } catch (error: Exception) {
            Log.e(TAG, "Existing paper signal query failed", error)
            return Result(
                scanned = signals.size,
                forwarded = 0,
                skipped = signals.size,
                message = "Existing paper signal query failed."
            )
        }

        var account = paperRepository.getAccount()
        if (account == null) {
            return Result(
                scanned = signals.size,
                forwarded = 0,
                skipped = signals.size,
                message = "Paper account is not configured."
            )
        }

        var forwarded = 0
        var skipped = 0
        signals.forEach { signal ->
            if (!isEligible(signal, now) || signal.id in existingSignalIds) {
                skipped++
                return@forEach
            }

            val config = configFor(signal)
            val candidate = candidateFor(signal, config)
            if (candidate == null) {
                skipped++
                return@forEach
            }

            val quantity = PaperSignalPolicy.initialQuantity(
                candidate = candidate,
                availableBalance = account?.availableBalance ?: 0.0,
                rules = sizingRules()
            )
            if (quantity <= 0) {
                skipped++
                return@forEach
            }

            try {
                paperRepository.saveSignalOrder(candidate, quantity)
                existingSignalIds.add(signal.id)
                forwarded++
                account = paperRepository.getAccount() ?: account
            } catch (error: Exception) {
                skipped++
                Log.w(
                    TAG,
                    "Could not forward signal ${signal.id} " +
                        "(${signal.symbol} ${signal.category.name}).",
                    error
                )
            }
        }

        Result(
            scanned = signals.size,
            forwarded = forwarded,
            skipped = skipped,
            message = if (forwarded > 0) {
                "Forwarded $forwarded signal(s) to paper trading."
            } else {
                "No new eligible signals to forward."
            }
        )
    }

    private fun isEligible(signal: ResearchSignal, now: Long): Boolean {
        if (signal.id <= 0L) return false
        if (signal.status !in eligibleStatuses) return false
        if (now >= signal.expiresAt) {
            return false
        }
        if (signal.generatedAt <= 0L) {
            return false
        }
        return signal.symbol.isNotBlank() && signal.exchange.isNotBlank()
    }

    private fun configFor(signal: ResearchSignal): StrategyConfig {
        return SignalJson.decodeConfig(signal.strategyConfigJson)
            ?: StrategyDefaults.forCategory(signal.category)
    }

    private fun candidateFor(
        signal: ResearchSignal,
        config: StrategyConfig
    ): PaperSignalCandidate? {
        val levels = signal.entryLow.isFinite() &&
            signal.entryHigh.isFinite() &&
            signal.stopLoss.isFinite() &&
            signal.target1.isFinite() &&
            signal.target2.isFinite() &&
            signal.target3.isFinite() &&
            signal.referencePrice.isFinite()
        if (!levels) return null

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
            costBpsPerSide = config.costBpsPerSide,
            slippageBpsPerSide = config.slippageBpsPerSide
        )
    }

    private fun sizingRules(): PaperSizingRules {
        val config = AppConfig.get()
        return PaperSizingRules(
            riskPerTradePercent = config.paperRiskPerTradePercent,
            allocationPercent = config.paperAllocationPercent,
            shortMarginRate = config.shortMarginRate,
            maxQuantity = config.maxOrderQuantity
        )
    }
}
