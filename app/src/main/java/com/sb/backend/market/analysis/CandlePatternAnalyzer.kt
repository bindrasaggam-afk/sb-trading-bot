package com.sb.backend.market.analysis

import com.sb.backend.market.model.MarketCandle
import kotlin.math.abs

/**
 * Deterministic OHLC candlestick pattern rules.
 *
 * Patterns describe candle geometry only; they are not standalone trade signals.
 * Context (trend, volume, support/resistance and confirmation) must be evaluated
 * separately before using a pattern in a trading decision.
 */
object CandlePatternAnalyzer {

    enum class Pattern {
        DOJI,
        HAMMER,
        SHOOTING_STAR,
        BULLISH_ENGULFING,
        BEARISH_ENGULFING,
        MORNING_STAR,
        EVENING_STAR
    }

    data class Match(
        val pattern: Pattern,
        val startTimestamp: Long,
        val endTimestamp: Long
    )

    fun detect(candles: List<MarketCandle>): List<Match> {
        if (candles.isEmpty()) return emptyList()
        val valid = candles.filter { it.isValid }.sortedBy { it.timestamp }
        if (valid.isEmpty()) return emptyList()

        val result = ArrayList<Match>()
        valid.forEachIndexed { index, candle ->
            if (isDoji(candle)) result.add(Match(Pattern.DOJI, candle.timestamp, candle.timestamp))
            if (isHammer(candle)) result.add(Match(Pattern.HAMMER, candle.timestamp, candle.timestamp))
            if (isShootingStar(candle)) result.add(Match(Pattern.SHOOTING_STAR, candle.timestamp, candle.timestamp))

            if (index >= 1) {
                val previous = valid[index - 1]
                if (isBullishEngulfing(previous, candle)) {
                    result.add(Match(Pattern.BULLISH_ENGULFING, previous.timestamp, candle.timestamp))
                }
                if (isBearishEngulfing(previous, candle)) {
                    result.add(Match(Pattern.BEARISH_ENGULFING, previous.timestamp, candle.timestamp))
                }
            }
            if (index >= 2) {
                val first = valid[index - 2]
                val middle = valid[index - 1]
                if (isMorningStar(first, middle, candle)) {
                    result.add(Match(Pattern.MORNING_STAR, first.timestamp, candle.timestamp))
                }
                if (isEveningStar(first, middle, candle)) {
                    result.add(Match(Pattern.EVENING_STAR, first.timestamp, candle.timestamp))
                }
            }
        }
        return result
    }

    fun isDoji(c: MarketCandle): Boolean {
        if (!c.isValid) return false
        val range = c.high - c.low
        return range > 0.0 && body(c) / range <= 0.10
    }

    fun isHammer(c: MarketCandle): Boolean {
        if (!c.isValid || isDoji(c)) return false
        val body = body(c)
        val lower = minOf(c.open, c.close) - c.low
        val upper = c.high - maxOf(c.open, c.close)
        return lower >= body * 2.0 && upper <= body * 0.5
    }

    fun isShootingStar(c: MarketCandle): Boolean {
        if (!c.isValid || isDoji(c)) return false
        val body = body(c)
        val lower = minOf(c.open, c.close) - c.low
        val upper = c.high - maxOf(c.open, c.close)
        return upper >= body * 2.0 && lower <= body * 0.5
    }

    fun isBullishEngulfing(previous: MarketCandle, current: MarketCandle): Boolean {
        if (!previous.isValid || !current.isValid) return false
        return previous.close < previous.open &&
            current.close > current.open &&
            current.open <= previous.close &&
            current.close >= previous.open
    }

    fun isBearishEngulfing(previous: MarketCandle, current: MarketCandle): Boolean {
        if (!previous.isValid || !current.isValid) return false
        return previous.close > previous.open &&
            current.close < current.open &&
            current.open >= previous.close &&
            current.close <= previous.open
    }

    fun isMorningStar(first: MarketCandle, middle: MarketCandle, last: MarketCandle): Boolean {
        if (!first.isValid || !middle.isValid || !last.isValid) return false
        val firstBody = body(first)
        val firstRange = first.high - first.low
        val middleRange = middle.high - middle.low
        if (first.close >= first.open || firstRange <= 0.0 || middleRange <= 0.0) return false
        val longFirst = firstBody / firstRange >= 0.50
        val smallMiddle = body(middle) <= firstBody * 0.30
        val closesAboveMidpoint = last.close > last.open &&
            last.close >= (first.open + first.close) / 2.0
        return longFirst && smallMiddle && closesAboveMidpoint
    }

    fun isEveningStar(first: MarketCandle, middle: MarketCandle, last: MarketCandle): Boolean {
        if (!first.isValid || !middle.isValid || !last.isValid) return false
        val firstBody = body(first)
        val firstRange = first.high - first.low
        val middleRange = middle.high - middle.low
        if (first.close <= first.open || firstRange <= 0.0 || middleRange <= 0.0) return false
        val longFirst = firstBody / firstRange >= 0.50
        val smallMiddle = body(middle) <= firstBody * 0.30
        val closesBelowMidpoint = last.close < last.open &&
            last.close <= (first.open + first.close) / 2.0
        return longFirst && smallMiddle && closesBelowMidpoint
    }

    private fun body(c: MarketCandle): Double = abs(c.close - c.open)
}
