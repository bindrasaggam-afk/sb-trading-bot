package com.sb.signals.services

import com.sb.backend.core.BackendManager
import com.sb.backend.market.MarketDataManager
import com.sb.backend.market.model.InstrumentKey
import com.sb.signals.data.SignalRepository
import com.sb.signals.domain.DataQuality
import com.sb.signals.domain.ResearchSignal
import com.sb.signals.domain.SignalAnalysisRun
import com.sb.signals.domain.SignalCategory
import com.sb.signals.domain.SignalLifecycleStatus
import com.sb.signals.domain.SignalStrategyVersion
import com.sb.signals.domain.StrategyConfig
import com.sb.signals.domain.StrategyDefaults
import com.sb.signals.domain.market.MarketSession
import com.sb.signals.domain.strategies.SignalRiskPlanner
import com.sb.signals.domain.strategies.SignalStrategyFactory
import com.sb.signals.domain.strategies.StrategyInput
import java.util.Locale

data class AnalysisSummary(
    val startedAt: Long,
    val finishedAt: Long,
    val symbolsEvaluated: Int,
    val signalsGenerated: Int,
    val signalsEvaluated: Int,
    val errors: Int,
    val message: String
)

class SignalAnalysisService(
    private val repository: SignalRepository,
    private val reader: SignalCandleReader = SignalCandleReader(),
    private val configs: Map<SignalCategory, StrategyConfig> =
        SignalCategory.values().associateWith {
            StrategyDefaults.forCategory(it)
        },
    private val universeProvider: () -> List<InstrumentKey> = {
        try {
            MarketDataManager.activeKeys()
        } catch (_: Exception) {
            emptyList()
        }
    },
    private val instrumentValid: (String, String) -> Boolean = {
            symbol,
            exchange ->
        try {
            if (!BackendManager.isInitialized()) {
                false
            } else {
                val instrument = BackendManager.instruments()
                    .findBySymbol(symbol, exchange)
                if (instrument == null) {
                    MarketSession.isTradeableInstrument(
                        symbol,
                        exchange,
                        null,
                        null
                    )
                } else {
                    instrument.isActive &&
                        MarketSession.isTradeableInstrument(
                            symbol,
                            exchange,
                            instrument.instrumentType,
                            instrument.category
                        )
                }
            }
        } catch (_: Exception) {
            false
        }
    }
) {

    fun seedStrategyVersions() {
        configs.values.forEach {
            repository.saveStrategyVersion(it)
        }
    }

    fun getStrategyVersions(): List<SignalStrategyVersion> =
        repository.getStrategyVersions()

    fun run(now: Long = System.currentTimeMillis()): AnalysisSummary {
        val symbols = universeProvider()
            .distinctBy { it.key() }
        var generated = 0
        var errors = 0
        val messages = ArrayList<String>()

        val evaluated = try {
            evaluateOutcomes(now)
        } catch (error: Exception) {
            errors++
            messages.add("Outcome evaluation: ${error.message}")
            0
        }

        var symbolsEvaluated = 0
        symbols.forEach { key ->
            if (!instrumentValid(key.symbol, key.exchange)) {
                return@forEach
            }
            symbolsEvaluated++
            SignalCategory.values().forEach { category ->
                try {
                    if (generate(
                            symbol = key.symbol,
                            exchange = key.exchange,
                            category = category,
                            now = now
                        ) != null
                    ) {
                        generated++
                    }
                } catch (error: Exception) {
                    errors++
                    if (messages.size < 5) {
                        messages.add(
                            "${key.symbol}/${category.name}: " +
                                (error.message ?: "unknown error")
                        )
                    }
                }
            }
        }

        val finished = System.currentTimeMillis()
        val message = if (messages.isEmpty()) {
            if (symbols.isEmpty()) {
                "No active subscribed instruments to analyse"
            } else {
                "Analysis complete"
            }
        } else {
            messages.joinToString("; ")
        }

        try {
            repository.recordRun(
                SignalAnalysisRun(
                    startedAt = now,
                    finishedAt = finished,
                    categories = SignalCategory.values()
                        .joinToString(",") { it.name },
                    symbolsEvaluated = symbolsEvaluated,
                    signalsGenerated = generated,
                    signalsEvaluated = evaluated,
                    errors = errors,
                    message = message
                )
            )
        } catch (_: Exception) {
        }

        return AnalysisSummary(
            startedAt = now,
            finishedAt = finished,
            symbolsEvaluated = symbolsEvaluated,
            signalsGenerated = generated,
            signalsEvaluated = evaluated,
            errors = errors,
            message = message
        )
    }

    fun generate(
        symbol: String,
        exchange: String,
        category: SignalCategory,
        now: Long = System.currentTimeMillis()
    ): ResearchSignal? {
        val normalizedSymbol = symbol.trim().uppercase(Locale.ROOT)
        val normalizedExchange = exchange.trim().uppercase(Locale.ROOT)
        if (normalizedSymbol.isBlank() || normalizedExchange.isBlank()) {
            return null
        }
        if (!instrumentValid(normalizedSymbol, normalizedExchange)) {
            return null
        }

        val config = configs[category]
            ?: StrategyDefaults.forCategory(category)
        val series = reader.read(
            symbol = normalizedSymbol,
            exchange = normalizedExchange,
            category = category,
            config = config,
            now = now
        )
        if (!series.quality.isUsable) return null
        val strategy = SignalStrategyFactory.create(config)
        val candidate = strategy.evaluate(
            StrategyInput(
                symbol = normalizedSymbol,
                exchange = normalizedExchange,
                now = now,
                primary = series.primary,
                confirmation = series.confirmation,
                trend = series.trend,
                config = config
            )
        ) ?: return null

        val lastCandle = series.primary.closed.lastOrNull() ?: return null
        val atrEntry = candidate.snapshot.values.entries
            .firstOrNull {
                it.key.startsWith("ATR") &&
                    it.value != null &&
                    it.value!! > 0.0
            }
        val atr = atrEntry?.value ?: return null
        val plan = SignalRiskPlanner.plan(
            direction = candidate.direction,
            close = lastCandle.close,
            atr = atr,
            category = category,
            holdingPeriod = candidate.holdingPeriod,
            exchange = normalizedExchange,
            now = now,
            config = config
        ) ?: return null
        if (plan.expiresAt <= now) return null

        val snapshotValues = LinkedHashMap(candidate.snapshot.values)
        snapshotValues["BullScore"] = candidate.bullScore
        snapshotValues["BearScore"] = candidate.bearScore
        val signal = ResearchSignal(
            symbol = normalizedSymbol,
            exchange = normalizedExchange,
            category = category,
            direction = candidate.direction,
            timeframe = if (category == SignalCategory.INTRADAY) {
                "5m"
            } else {
                "1D"
            },
            strategyId = config.strategyId,
            strategyVersion = config.version,
            candleTimestamp = lastCandle.timestamp,
            generatedAt = now,
            referencePrice = plan.referencePrice,
            entryLow = plan.levels.entryLow,
            entryHigh = plan.levels.entryHigh,
            stopLoss = plan.levels.stopLoss,
            target1 = plan.levels.target1,
            target2 = plan.levels.target2,
            target3 = plan.levels.target3,
            riskPerUnit = plan.riskPerUnit,
            rewardRisk1 = plan.rewardRisk1,
            rewardRisk2 = plan.rewardRisk2,
            rewardRisk3 = plan.rewardRisk3,
            confidence = candidate.score,
            holdingPeriod = candidate.holdingPeriod,
            expiresAt = plan.expiresAt,
            status = SignalLifecycleStatus.NEW,
            reasons = candidate.reasons,
            indicatorSnapshot = candidate.snapshot.copy(
                values = snapshotValues
            ),
            dataQuality = DataQuality(
                score = series.quality.score,
                candleCount = series.quality.candleCount,
                firstTimestamp = series.quality.firstTimestamp,
                lastTimestamp = series.quality.lastTimestamp,
                notes = series.quality.notes
            )
        )

        val id = repository.insert(signal)
        return if (id > 0L) signal.copy(id = id) else null
    }

    fun evaluateOutcomes(now: Long = System.currentTimeMillis()): Int {
        var evaluated = 0
        repository.getOpenSignals().forEach { signal ->
            val config = configs[signal.category]
                ?: StrategyDefaults.forCategory(signal.category)
            val future = reader.futureCandles(
                symbol = signal.symbol,
                exchange = signal.exchange,
                timeframe = signal.timeframe,
                fromTimestamp = signal.candleTimestamp,
                toTimestamp = now
            )
            val result = SignalOutcomeEvaluator.evaluate(
                signal = signal,
                futureCandles = future,
                now = now,
                config = config
            ) ?: return@forEach

            val changed = result.status != signal.status ||
                result.triggeredAt != signal.triggeredAt ||
                result.target1At != signal.target1At ||
                result.target2At != signal.target2At ||
                result.target3At != signal.target3At ||
                result.stoppedAt != signal.stoppedAt ||
                result.closedAt != signal.closedAt ||
                result.exitPrice != signal.exitPrice

            val after = signal.copy(
                status = result.status,
                triggeredAt = result.triggeredAt ?: signal.triggeredAt,
                target1At = result.target1At ?: signal.target1At,
                target2At = result.target2At ?: signal.target2At,
                target3At = result.target3At ?: signal.target3At,
                stoppedAt = result.stoppedAt ?: signal.stoppedAt,
                closedAt = result.closedAt ?: signal.closedAt,
                exitPrice = result.exitPrice ?: signal.exitPrice,
                outcomeR = result.outcomeR ?: signal.outcomeR,
                outcomePnlPercent = result.outcomePnlPercent
                    ?: signal.outcomePnlPercent,
                outcomeNetPnlPercent = result.outcomeNetPnlPercent
                    ?: signal.outcomeNetPnlPercent,
                outcomeCostPercent = result.outcomeCostPercent
                    ?: signal.outcomeCostPercent,
                outcomeReason = if (result.status.isCompleted || changed) {
                    result.reason
                } else {
                    signal.outcomeReason
                },
                ambiguousBar = signal.ambiguousBar || result.ambiguousBar,
                maxFavorableExcursionPercent = result.mfePercent
                    ?: signal.maxFavorableExcursionPercent,
                maxAdverseExcursionPercent = result.maePercent
                    ?: signal.maxAdverseExcursionPercent,
                lastEvaluatedAt = now
            )
            if (repository.applyEvaluation(signal, after)) {
                evaluated++
            }
        }
        return evaluated
    }
}
