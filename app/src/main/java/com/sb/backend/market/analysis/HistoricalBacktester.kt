package com.sb.backend.market.analysis

import com.sb.backend.market.model.MarketCandle
import kotlin.math.max
import kotlin.math.min

/**
 * Causal, single-position historical evaluator.
 *
 * The signal callback sees candles only through the current closed candle.
 * Entry is at the next candle open, never at the signal candle close.
 * If stop and target are both touched in one candle, stop is assumed first.
 */
object HistoricalBacktester {

    enum class Direction { LONG, SHORT }
    enum class ExitReason { STOP_LOSS, TARGET, TIME_LIMIT }
    enum class Outcome { WIN, LOSS, BREAKEVEN }

    data class Candidate(
        val direction: Direction,
        val stopLoss: Double,
        val target: Double
    )

    data class Config(
        val initialCapital: Double = 100_000.0,
        val feeBpsPerSide: Double = 5.0,
        val slippageBpsPerSide: Double = 2.0,
        val maxHoldingCandles: Int = 20
    ) {
        init {
            require(initialCapital > 0.0 && initialCapital.isFinite())
            require(feeBpsPerSide >= 0.0 && feeBpsPerSide.isFinite())
            require(slippageBpsPerSide >= 0.0 && slippageBpsPerSide.isFinite())
            require(maxHoldingCandles > 0)
        }
    }

    data class Trade(
        val entryTimestamp: Long,
        val exitTimestamp: Long,
        val direction: Direction,
        val entryPrice: Double,
        val exitPrice: Double,
        val netReturnPct: Double,
        val outcome: Outcome,
        val exitReason: ExitReason
    )

    data class Report(
        val trades: List<Trade>,
        val evaluatedBars: Int,
        val wins: Int,
        val losses: Int,
        val breakeven: Int,
        val winRatePct: Double,
        val coveragePct: Double,
        val netReturnPct: Double,
        val profitFactor: Double,
        val maxDrawdownPct: Double,
        val longWinRatePct: Double,
        val shortWinRatePct: Double
    )

    fun evaluateOutOfSample(
        candles: List<MarketCandle>,
        outOfSampleStartIndex: Int,
        minimumHistory: Int = 200,
        config: Config = Config(),
        signalProvider: (List<MarketCandle>) -> Candidate?
    ): Report {
        require(minimumHistory >= 1)
        val series = candles.filter { it.isValid }.distinctBy { it.timestamp }.sortedBy { it.timestamp }
        require(outOfSampleStartIndex >= minimumHistory)
        require(outOfSampleStartIndex < series.size)

        val trades = ArrayList<Trade>()
        val evaluatedBars = series.size - outOfSampleStartIndex
        var index = outOfSampleStartIndex
        var equity = config.initialCapital
        var peakEquity = equity
        var maxDrawdown = 0.0

        while (index < series.lastIndex) {
            val history = series.subList(0, index + 1)
            val candidate = signalProvider(history)
            if (candidate == null) {
                index++
                continue
            }

            val entryIndex = index + 1
            val entryCandle = series[entryIndex]
            val slip = config.slippageBpsPerSide / 10_000.0
            val entryPrice = when (candidate.direction) {
                Direction.LONG -> entryCandle.open * (1.0 + slip)
                Direction.SHORT -> entryCandle.open * (1.0 - slip)
            }
            val validRisk = when (candidate.direction) {
                Direction.LONG -> candidate.stopLoss < entryPrice && candidate.target > entryPrice
                Direction.SHORT -> candidate.stopLoss > entryPrice && candidate.target < entryPrice
            }
            if (!entryPrice.isFinite() || entryPrice <= 0.0 || !validRisk) {
                index++
                continue
            }

            val finalIndex = min(series.lastIndex, entryIndex + config.maxHoldingCandles - 1)
            var exitIndex = finalIndex
            var rawExit = series[finalIndex].close
            var reason = ExitReason.TIME_LIMIT

            for (futureIndex in entryIndex..finalIndex) {
                val candle = series[futureIndex]
                val stopHit: Boolean
                val targetHit: Boolean
                when (candidate.direction) {
                    Direction.LONG -> {
                        stopHit = candle.low <= candidate.stopLoss
                        targetHit = candle.high >= candidate.target
                    }
                    Direction.SHORT -> {
                        stopHit = candle.high >= candidate.stopLoss
                        targetHit = candle.low <= candidate.target
                    }
                }
                if (stopHit || targetHit) {
                    exitIndex = futureIndex
                    // Conservative intrabar assumption when both levels touched.
                    if (stopHit) {
                        // A gap through the stop is filled at the worse candle
                        // open, not at the unreachable stop price.
                        rawExit = when (candidate.direction) {
                            Direction.LONG -> min(candidate.stopLoss, candle.open)
                            Direction.SHORT -> max(candidate.stopLoss, candle.open)
                        }
                        reason = ExitReason.STOP_LOSS
                    } else {
                        // A favourable gap through target receives the open.
                        rawExit = when (candidate.direction) {
                            Direction.LONG -> max(candidate.target, candle.open)
                            Direction.SHORT -> min(candidate.target, candle.open)
                        }
                        reason = ExitReason.TARGET
                    }
                    break
                }
            }

            val exitPrice = when (candidate.direction) {
                Direction.LONG -> rawExit * (1.0 - slip)
                Direction.SHORT -> rawExit * (1.0 + slip)
            }
            val grossReturn = when (candidate.direction) {
                Direction.LONG -> (exitPrice - entryPrice) / entryPrice
                Direction.SHORT -> (entryPrice - exitPrice) / entryPrice
            }
            val fees = 2.0 * config.feeBpsPerSide / 10_000.0
            val netReturn = (grossReturn - fees) * 100.0
            val outcome = when {
                netReturn > 0.000001 -> Outcome.WIN
                netReturn < -0.000001 -> Outcome.LOSS
                else -> Outcome.BREAKEVEN
            }

            trades.add(
                Trade(
                    entryTimestamp = entryCandle.timestamp,
                    exitTimestamp = series[exitIndex].timestamp,
                    direction = candidate.direction,
                    entryPrice = entryPrice,
                    exitPrice = exitPrice,
                    netReturnPct = netReturn,
                    outcome = outcome,
                    exitReason = reason
                )
            )

            equity *= 1.0 + netReturn / 100.0
            peakEquity = max(peakEquity, equity)
            if (peakEquity > 0.0) {
                maxDrawdown = max(maxDrawdown, (peakEquity - equity) / peakEquity * 100.0)
            }
            index = exitIndex + 1
        }

        val wins = trades.count { it.outcome == Outcome.WIN }
        val losses = trades.count { it.outcome == Outcome.LOSS }
        val breakeven = trades.count { it.outcome == Outcome.BREAKEVEN }
        val grossWins = trades.filter { it.netReturnPct > 0.0 }.sumOf { it.netReturnPct }
        val grossLosses = -trades.filter { it.netReturnPct < 0.0 }.sumOf { it.netReturnPct }
        val longTrades = trades.filter { it.direction == Direction.LONG }
        val shortTrades = trades.filter { it.direction == Direction.SHORT }

        fun winRate(items: List<Trade>): Double =
            if (items.isEmpty()) 0.0 else items.count { it.outcome == Outcome.WIN }.toDouble() / items.size * 100.0

        return Report(
            trades = trades,
            evaluatedBars = evaluatedBars,
            wins = wins,
            losses = losses,
            breakeven = breakeven,
            winRatePct = winRate(trades),
            coveragePct = if (evaluatedBars == 0) 0.0 else trades.size.toDouble() / evaluatedBars * 100.0,
            netReturnPct = (equity / config.initialCapital - 1.0) * 100.0,
            profitFactor = if (grossLosses == 0.0) if (grossWins > 0.0) Double.POSITIVE_INFINITY else 0.0 else grossWins / grossLosses,
            maxDrawdownPct = maxDrawdown,
            longWinRatePct = winRate(longTrades),
            shortWinRatePct = winRate(shortTrades)
        )
    }
}
