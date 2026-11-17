package com.sb.signals.domain.strategies

import com.sb.backend.market.model.MarketCandle
import com.sb.signals.domain.DataQuality
import com.sb.signals.domain.HoldingPeriod
import com.sb.signals.domain.SignalCategory
import com.sb.signals.domain.SignalDirection
import com.sb.signals.domain.SignalIndicatorSnapshot
import com.sb.signals.domain.StrategyCandidate
import com.sb.signals.domain.StrategyConfig
import com.sb.signals.domain.indicators.IndicatorComputation
import com.sb.signals.domain.indicators.SignalIndicatorEngine
import com.sb.signals.domain.market.MarketSession
import com.sb.signals.domain.market.PreparedSeries
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class StrategyInput(
    val symbol: String,
    val exchange: String,
    val now: Long,
    val primary: PreparedSeries,
    val confirmation: PreparedSeries,
    val trend: PreparedSeries,
    val config: StrategyConfig
)

interface SignalStrategy {
    val config: StrategyConfig

    fun evaluate(input: StrategyInput): StrategyCandidate?
}

class TrendSignalStrategy(
    override val config: StrategyConfig
) : SignalStrategy {

    override fun evaluate(input: StrategyInput): StrategyCandidate? {
        if (!input.primary.quality.isUsable) return null
        if (input.primary.closed.size < config.minPrimaryCandles) return null
        if (input.confirmation.closed.size < config.minConfirmationCandles) {
            return null
        }

        val category = config.category
        if (category == SignalCategory.INTRADAY) {
            if (!MarketSession.isEntryWindow(input.now, input.exchange)) {
                return null
            }
            val lastSession = com.sb.backend.market.TimeframeBuckets.sessionKey(
                input.primary.closed.last().timestamp
            )
            val currentSession =
                com.sb.backend.market.TimeframeBuckets.sessionKey(input.now)
            if (lastSession != currentSession) return null
        }

        val primary = SignalIndicatorEngine.compute(
            input.primary.closed,
            config
        )
        val confirmationCandles = input.confirmation.closed
        val confirmation = SignalIndicatorEngine.compute(
            confirmationCandles,
            config
        )
        val trendCandles = input.trend.closed.ifEmpty {
            confirmationCandles
        }
        val trend = SignalIndicatorEngine.compute(trendCandles, config)
        val close = input.primary.closed.last().close
        val atr = primary.latest("ATR${config.atrPeriod}") ?: return null
        val emaFast = primary.latest("EMA${config.emaFast}") ?: return null
        val emaSlow = primary.latest("EMA${config.emaSlow}") ?: return null
        val adx = primary.latest("ADX${config.adxPeriod}") ?: return null
        if (close <= 0.0 || atr <= 0.0 || !atr.isFinite()) return null

        val bull = score(
            direction = SignalDirection.BUY,
            primary = primary,
            confirmation = confirmation,
            trend = trend,
            candles = input.primary.closed
        )
        val bear = score(
            direction = SignalDirection.SELL,
            primary = primary,
            confirmation = confirmation,
            trend = trend,
            candles = input.primary.closed
        )

        val chosen = when {
            bull.score >= bear.score -> bull
            else -> bear
        }
        if (chosen.score < config.minScore) return null
        if (abs(bull.score - bear.score) < config.minScoreLead) return null
        if (chosen.direction == SignalDirection.SELL &&
            !shortsAllowed(input.exchange)
        ) {
            return null
        }
        if (chosen.direction == SignalDirection.SELL &&
            category == SignalCategory.DELIVERY
        ) {
            return null
        }
        if (category == SignalCategory.DELIVERY &&
            chosen.direction != SignalDirection.BUY
        ) {
            return null
        }
        if (category == SignalCategory.DELIVERY) {
            val emaTrend = primary.latest("EMA${config.emaTrend}")
            if (emaTrend != null && close < emaTrend) return null
            if (emaSlow > 0.0 && emaFast < emaSlow) return null
        }
        if (category == SignalCategory.SWING &&
            chosen.direction == SignalDirection.BUY &&
            emaFast < emaSlow
        ) {
            return null
        }

        val holdingPeriod = holdingPeriod(
            category = category,
            primary = primary,
            confirmation = confirmation,
            adx = adx,
            close = close,
            atr = atr
        )
        val mergedValues = LinkedHashMap<String, Double?>()
        mergedValues.putAll(primary.values)
        mergedValues["HigherClose"] =
            confirmation.latest("Close")
        mergedValues["HigherEMA${config.emaFast}"] =
            confirmation.latest("EMA${config.emaFast}")
        mergedValues["HigherEMA${config.emaSlow}"] =
            confirmation.latest("EMA${config.emaSlow}")
        mergedValues["TrendClose"] =
            trend.latest("Close")

        return StrategyCandidate(
            direction = chosen.direction,
            score = chosen.score,
            bullScore = bull.score,
            bearScore = bear.score,
            reasons = chosen.reasons,
            snapshot = SignalIndicatorSnapshot(
                values = mergedValues,
                confirmations = chosen.confirmations,
                scores = chosen.scores
            ),
            holdingPeriod = holdingPeriod
        )
    }

    private fun shortsAllowed(exchange: String): Boolean {
        if (!config.allowShorts) return false
        return when (config.category) {
            SignalCategory.INTRADAY -> true
            SignalCategory.SWING -> MarketSession.supportsOvernightShort(exchange)
            SignalCategory.DELIVERY -> false
        }
    }

    private fun holdingPeriod(
        category: SignalCategory,
        primary: IndicatorComputation,
        confirmation: IndicatorComputation,
        adx: Double,
        close: Double,
        atr: Double
    ): HoldingPeriod {
        if (category == SignalCategory.INTRADAY) return HoldingPeriod.ONE_DAY
        if (category == SignalCategory.DELIVERY) {
            return config.deliveryHoldingPeriod
        }

        val higherFast = confirmation.latest("EMA${config.emaFast}")
        val higherSlow = confirmation.latest("EMA${config.emaSlow}")
        val higherClose = confirmation.latest("Close")
        val higherAligned = higherFast != null &&
            higherSlow != null &&
            higherClose != null &&
            higherFast > higherSlow &&
            higherClose > higherSlow
        val atrPercent = if (close > 0.0) atr / close * 100.0 else 0.0

        return when {
            !higherAligned && adx >= 28.0 && atrPercent >= 2.0 ->
                HoldingPeriod.THREE_DAYS
            !higherAligned -> HoldingPeriod.ONE_WEEK
            adx >= 32.0 && atrPercent >= 2.0 -> HoldingPeriod.THREE_MONTHS
            adx >= 28.0 -> HoldingPeriod.TWO_MONTHS
            adx >= 24.0 -> HoldingPeriod.ONE_MONTH
            adx >= 21.0 -> HoldingPeriod.TWO_WEEKS
            else -> HoldingPeriod.ONE_WEEK
        }
    }

    private data class DirectionScore(
        val direction: SignalDirection,
        val score: Double,
        val reasons: List<String>,
        val confirmations: Map<String, Boolean>,
        val scores: Map<String, Double?>
    )

    private fun score(
        direction: SignalDirection,
        primary: IndicatorComputation,
        confirmation: IndicatorComputation,
        trend: IndicatorComputation,
        candles: List<MarketCandle>
    ): DirectionScore {
        val close = primary.latest("Close") ?: return emptyScore(direction)
        val emaFast = primary.latest("EMA${config.emaFast}")
        val emaSlow = primary.latest("EMA${config.emaSlow}")
        val emaTrend = primary.latest("EMA${config.emaTrend}")
        val rsi = primary.latest("RSI${config.rsiPeriod}")
        val macdLine = primary.latest("MACD")
        val macdSignal = primary.latest("MACDSignal")
        val macdHistogram = primary.latest("MACDHistogram")
        val supertrendDirection = primary.latest("SupertrendDirection")
        val adx = primary.latest("ADX${config.adxPeriod}")
        val plusDi = primary.latest("PlusDI")
        val minusDi = primary.latest("MinusDI")
        val vwap = primary.latest("VWAP")
        val volume = primary.latest("Volume")
        val volumeMa = primary.latest("VolumeMA${config.volumePeriod}")
        val bbMiddle = primary.latest("BBMiddle")
        val bbUpper = primary.latest("BBUpper")
        val bbLower = primary.latest("BBLower")
        val bbMiddleSeries = primary.series["BBMiddle"]
        val bbMiddlePast = bbMiddleSeries?.getOrNull(
            max(0, primary.series["BBMiddle"]!!.lastIndex - 5)
        )

        val confirmations = linkedMapOf<String, Boolean>()
        val scores = linkedMapOf<String, Double?>()
        val reasons = ArrayList<String>()
        var total = 0.0

        val trendAligned = emaFast != null &&
            emaSlow != null &&
            when (direction) {
                SignalDirection.BUY -> emaFast > emaSlow
                SignalDirection.SELL -> emaFast < emaSlow
            }
        val aboveLongTrend = emaTrend != null &&
            when (direction) {
                SignalDirection.BUY -> close > emaTrend
                SignalDirection.SELL -> close < emaTrend
            }
        val trendScore = when {
            trendAligned && (emaTrend == null || aboveLongTrend) -> 18.0
            trendAligned -> 12.0
            aboveLongTrend -> 7.0
            else -> 0.0
        }
        total += trendScore
        scores["EMA trend"] = trendScore
        confirmations["EMA trend"] = trendScore >= 18.0
        if (trendScore > 0.0) reasons.add("EMA structure aligns")

        val rsiScore = when (direction) {
            SignalDirection.BUY -> when {
                rsi == null -> 0.0
                rsi in 55.0..70.0 -> 10.0
                rsi >= 50.0 && rsi < 55.0 -> 6.0
                rsi >= 45.0 && rsi < 50.0 -> 2.0
                else -> 0.0
            }
            SignalDirection.SELL -> when {
                rsi == null -> 0.0
                rsi in 30.0..45.0 -> 10.0
                rsi in 45.0..50.0 -> 6.0
                rsi in 50.0..55.0 -> 2.0
                else -> 0.0
            }
        }
        total += rsiScore
        scores["RSI"] = rsiScore
        confirmations["RSI"] = rsiScore >= 10.0
        if (rsiScore >= 6.0) reasons.add("Momentum RSI confirms")

        val macdAligned = macdLine != null &&
            macdSignal != null &&
            when (direction) {
                SignalDirection.BUY -> macdLine > macdSignal
                SignalDirection.SELL -> macdLine < macdSignal
            }
        val histogramAligned = macdHistogram != null &&
            when (direction) {
                SignalDirection.BUY -> macdHistogram > 0.0
                SignalDirection.SELL -> macdHistogram < 0.0
            }
        val macdScore = when {
            macdAligned && histogramAligned -> 10.0
            macdAligned || histogramAligned -> 6.0
            else -> 0.0
        }
        total += macdScore
        scores["MACD"] = macdScore
        confirmations["MACD"] = macdScore >= 10.0
        if (macdScore >= 6.0) reasons.add("MACD momentum confirms")

        val supertrendAligned = supertrendDirection != null &&
            when (direction) {
                SignalDirection.BUY -> supertrendDirection > 0.0
                SignalDirection.SELL -> supertrendDirection < 0.0
            }
        val supertrendScore = if (supertrendAligned) 10.0 else 0.0
        total += supertrendScore
        scores["Supertrend"] = supertrendScore
        confirmations["Supertrend"] = supertrendAligned
        if (supertrendAligned) reasons.add("Supertrend direction aligns")

        val diAligned = plusDi != null &&
            minusDi != null &&
            when (direction) {
                SignalDirection.BUY -> plusDi > minusDi
                SignalDirection.SELL -> minusDi > plusDi
            }
        val adxScore = when {
            adx == null -> 0.0
            adx >= 25.0 && diAligned -> 10.0
            adx >= config.minAdx && diAligned -> 7.0
            adx >= config.minAdx -> 4.0
            else -> 0.0
        }
        total += adxScore
        scores["ADX"] = adxScore
        confirmations["ADX"] = adxScore >= 7.0
        if (adxScore >= 7.0) reasons.add("ADX confirms directional strength")

        val vwapAligned = vwap != null &&
            when (direction) {
                SignalDirection.BUY -> close > vwap
                SignalDirection.SELL -> close < vwap
            }
        val vwapScore = if (vwapAligned) 7.0 else 0.0
        total += vwapScore
        scores["VWAP"] = vwapScore
        confirmations["VWAP"] = vwapAligned
        if (vwapAligned) reasons.add("Price is on the signal side of VWAP")

        val volumeRatio = if (volume != null &&
            volumeMa != null &&
            volumeMa > 0.0
        ) {
            volume / volumeMa
        } else {
            0.0
        }
        val volumeScore = when {
            volumeRatio >= 1.5 -> 7.0
            volumeRatio >= config.volumeMultiplier -> 5.0
            volumeRatio >= 1.0 -> 2.0
            else -> 0.0
        }
        total += volumeScore
        scores["Volume"] = volumeScore
        confirmations["Volume"] = volumeScore >= 5.0
        if (volumeScore >= 5.0) reasons.add("Volume expansion supports the move")

        val previous = candles.dropLast(1)
        val breakoutWindow = previous.takeLast(20)
        val midpointWindow = previous.takeLast(10)
        val breakout = when (direction) {
            SignalDirection.BUY -> when {
                breakoutWindow.isNotEmpty() &&
                    close > breakoutWindow.maxOf { it.high } -> 10.0
                midpointWindow.isNotEmpty() &&
                    close > midpointWindow.maxOf { it.high } -> 7.0
                emaFast != null && close > emaFast -> 3.0
                else -> 0.0
            }
            SignalDirection.SELL -> when {
                breakoutWindow.isNotEmpty() &&
                    close < breakoutWindow.minOf { it.low } -> 10.0
                midpointWindow.isNotEmpty() &&
                    close < midpointWindow.minOf { it.low } -> 7.0
                emaFast != null && close < emaFast -> 3.0
                else -> 0.0
            }
        }
        total += breakout
        scores["Breakout"] = breakout
        confirmations["Breakout"] = breakout >= 7.0
        if (breakout >= 7.0) reasons.add("Range breakout is confirmed")

        val bollingerScore = when (direction) {
            SignalDirection.BUY -> when {
                bbMiddle == null || bbUpper == null || bbLower == null -> 0.0
                close > bbMiddle && close <= bbUpper &&
                    bbMiddlePast != null && bbMiddle > bbMiddlePast -> 8.0
                close > bbMiddle && close < bbUpper -> 5.0
                close <= bbUpper -> 2.0
                else -> 0.0
            }
            SignalDirection.SELL -> when {
                bbMiddle == null || bbUpper == null || bbLower == null -> 0.0
                close < bbMiddle && close >= bbLower &&
                    bbMiddlePast != null && bbMiddle < bbMiddlePast -> 8.0
                close < bbMiddle && close > bbLower -> 5.0
                close >= bbLower -> 2.0
                else -> 0.0
            }
        }
        total += bollingerScore
        scores["Bollinger"] = bollingerScore
        confirmations["Bollinger"] = bollingerScore >= 5.0
        if (bollingerScore >= 5.0) reasons.add("Bollinger structure confirms")

        val higherClose = confirmation.latest("Close")
        val higherFast = confirmation.latest("EMA${config.emaFast}")
        val higherSlow = confirmation.latest("EMA${config.emaSlow}")
        val higherAligned = higherClose != null &&
            higherFast != null &&
            higherSlow != null &&
            when (direction) {
                SignalDirection.BUY ->
                    higherClose > higherSlow && higherFast > higherSlow
                SignalDirection.SELL ->
                    higherClose < higherSlow && higherFast < higherSlow
            }
        val higherPartial = higherClose != null &&
            higherSlow != null &&
            when (direction) {
                SignalDirection.BUY -> higherClose > higherSlow
                SignalDirection.SELL -> higherClose < higherSlow
            }
        val higherScore = when {
            higherAligned -> 10.0
            higherPartial -> 5.0
            else -> 0.0
        }
        total += higherScore
        scores["Higher timeframe"] = higherScore
        confirmations["Higher timeframe"] = higherAligned
        if (higherScore >= 5.0) {
            reasons.add("Higher timeframe trend agrees")
        }

        val trendClose = trend.latest("Close")
        if (trendClose != null) {
            confirmations["Trend timeframe"] = when (direction) {
                SignalDirection.BUY -> close > trendClose
                SignalDirection.SELL -> close < trendClose
            }
        }

        return DirectionScore(
            direction = direction,
            score = total.coerceIn(0.0, 100.0),
            reasons = reasons.distinct(),
            confirmations = confirmations,
            scores = scores
        )
    }

    private fun emptyScore(direction: SignalDirection): DirectionScore =
        DirectionScore(
            direction = direction,
            score = 0.0,
            reasons = emptyList(),
            confirmations = emptyMap(),
            scores = emptyMap()
        )
}

object SignalStrategyFactory {
    fun create(config: StrategyConfig): SignalStrategy =
        TrendSignalStrategy(config)
}

object SignalQualityCombiner {
    fun combine(
        primary: DataQuality,
        confirmation: DataQuality,
        trend: DataQuality
    ): DataQuality {
        val notes = ArrayList<String>()
        notes.addAll(primary.notes)
        notes.addAll(confirmation.notes.map { "Confirmation: $it" })
        notes.addAll(trend.notes.map { "Trend: $it" })
        return DataQuality(
            score = min(
                primary.score,
                min(confirmation.score, trend.score)
            ),
            candleCount = primary.candleCount,
            firstTimestamp = primary.firstTimestamp,
            lastTimestamp = primary.lastTimestamp,
            notes = notes.distinct()
        )
    }
}
