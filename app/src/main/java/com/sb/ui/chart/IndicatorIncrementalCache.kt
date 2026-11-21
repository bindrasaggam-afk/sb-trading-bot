package com.sb.ui.chart

import com.sb.backend.market.TimeframeBuckets
import com.sb.ui.chart.config.IndicatorConfig
import com.sb.ui.chart.config.IndicatorConfigPolicy
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class IndicatorIncrementalCache {

    data class Result(
        val ema20: DoubleArray,
        val sma50: DoubleArray,
        val vwap: DoubleArray,
        val rsi14: DoubleArray,
        val macd: IndicatorMath.MacdResult?,
        val bollinger: IndicatorMath.BollingerResult?,
        val supertrend: IndicatorMath.SupertrendResult?
    )

    private data class LastValues(
        val ema20: Double,
        val sma50: Double,
        val vwap: Double,
        val rsi14: Double,
        val macd: Double,
        val signal: Double,
        val histogram: Double,
        val bollingerMiddle: Double,
        val bollingerUpper: Double,
        val bollingerLower: Double,
        val supertrendLine: Double,
        val supertrendDirection: Int
    )

    private var candles: List<ChartCandle> = emptyList()
    private var indicatorKey: Set<ChartIndicator> = emptySet()
    private var timeframeKey: String = ""
    private var activeConfig: IndicatorConfig = IndicatorConfigPolicy.DEFAULT
    private var result: Result = emptyResult()
    private var states = States()
    private var beforeLastStates = States()

    val lastResult: Result get() = result

    @JvmOverloads
    fun update(
        candles: List<ChartCandle>,
        indicators: Set<ChartIndicator>,
        timeframeKey: String = ""
    ): Result = update(candles, IndicatorConfigPolicy.fromLegacy(indicators, timeframeKey))

    fun update(candles: List<ChartCandle>, config: IndicatorConfig): Result {
        val active = IndicatorConfigPolicy.normalize(config)
        val key = active.enabled
        if (this.candles == candles &&
            this.indicatorKey == key &&
            this.timeframeKey == active.timeframeKey &&
            this.activeConfig == active
        ) {
            return result
        }
        if (this.indicatorKey != key ||
            this.timeframeKey != active.timeframeKey ||
            this.activeConfig != active
        ) {
            return fullRecalculate(candles, key, active.timeframeKey, active)
        }

        val previous = this.candles
        if (previous.isEmpty() ||
            !hasCompleteStates(states, key) ||
            !hasCompleteStates(beforeLastStates, key) ||
            !hasCompleteResult(previous.size, key)
        ) {
            return fullRecalculate(candles, key, active.timeframeKey, active)
        }

        val isAppend = candles.size == previous.size + 1 &&
            previous.isNotEmpty() &&
            samePrefix(previous, candles, previous.size) &&
            candles.last().timestamp > previous.last().timestamp
        val isLastOnly = !isAppend &&
            candles.size == previous.size &&
            candles.isNotEmpty() &&
            samePrefix(candles, previous, candles.size - 1)

        if (!isAppend && !isLastOnly) {
            return fullRecalculate(candles, key, active.timeframeKey, active)
        }

        val nextStates = if (isAppend) states.copy() else beforeLastStates.copy()
        val lastIndex = candles.size - 1
        val values = feedLast(candles[lastIndex], lastIndex, nextStates, active)

        if (isAppend) {
            beforeLastStates = states.copy()
        }
        states = nextStates

        result = Result(
            ema20 = if (ChartIndicator.EMA_20 in key) {
                copyAndSet(result.ema20, lastIndex, values.ema20, isAppend)
            } else DoubleArray(0),
            sma50 = if (ChartIndicator.SMA_50 in key) {
                copyAndSet(result.sma50, lastIndex, values.sma50, isAppend)
            } else DoubleArray(0),
            vwap = if (ChartIndicator.VWAP in key) {
                copyAndSet(result.vwap, lastIndex, values.vwap, isAppend)
            } else DoubleArray(0),
            rsi14 = if (ChartIndicator.RSI_14 in key) {
                copyAndSet(result.rsi14, lastIndex, values.rsi14, isAppend)
            } else DoubleArray(0),
            macd = result.macd?.let { previousResult ->
                IndicatorMath.MacdResult(
                    macd = copyAndSet(previousResult.macd, lastIndex, values.macd, isAppend),
                    signal = copyAndSet(previousResult.signal, lastIndex, values.signal, isAppend),
                    histogram = copyAndSet(previousResult.histogram, lastIndex, values.histogram, isAppend)
                )
            },
            bollinger = result.bollinger?.let { previousResult ->
                IndicatorMath.BollingerResult(
                    middle = copyAndSet(previousResult.middle, lastIndex, values.bollingerMiddle, isAppend),
                    upper = copyAndSet(previousResult.upper, lastIndex, values.bollingerUpper, isAppend),
                    lower = copyAndSet(previousResult.lower, lastIndex, values.bollingerLower, isAppend)
                )
            },
            supertrend = result.supertrend?.let { previousResult ->
                val line = copyAndSet(previousResult.line, lastIndex, values.supertrendLine, isAppend)
                val direction = previousResult.direction.copyOf(
                    if (isAppend) lastIndex + 1 else previousResult.direction.size
                )
                direction[lastIndex] = values.supertrendDirection
                IndicatorMath.SupertrendResult(line, direction)
            }
        )
        this.candles = ArrayList(candles)
        this.indicatorKey = key
        this.timeframeKey = active.timeframeKey
        this.activeConfig = active
        return result
    }

    private fun fullRecalculate(
        candles: List<ChartCandle>,
        indicators: Set<ChartIndicator>,
        timeframeKey: String,
        config: IndicatorConfig
    ): Result {
        val closes = config.source.values(candles)
        val full = Result(
            ema20 = if (ChartIndicator.EMA_20 in indicators) {
                IndicatorMath.ema(closes, config.emaPeriod)
            } else DoubleArray(0),
            sma50 = if (ChartIndicator.SMA_50 in indicators) {
                IndicatorMath.sma(closes, config.smaPeriod)
            } else DoubleArray(0),
            vwap = if (ChartIndicator.VWAP in indicators) {
                IndicatorMath.vwap(candles)
            } else DoubleArray(0),
            rsi14 = if (ChartIndicator.RSI_14 in indicators) {
                IndicatorMath.rsi(closes, config.rsiPeriod)
            } else DoubleArray(0),
            macd = if (ChartIndicator.MACD in indicators) {
                IndicatorMath.macd(closes, config.macdFast, config.macdSlow, config.macdSignal)
            } else null,
            bollinger = if (ChartIndicator.BOLLINGER in indicators) {
                IndicatorMath.bollinger(closes, config.bollingerPeriod, config.bollingerMultiplier)
            } else null,
            supertrend = if (ChartIndicator.SUPERTREND in indicators) {
                IndicatorMath.supertrend(candles, config.supertrendPeriod, config.supertrendMultiplier)
            } else null
        )

        val fresh = States()
        fresh.ema20 = if (ChartIndicator.EMA_20 in indicators) EmaState(config.emaPeriod) else null
        fresh.sma50 = if (ChartIndicator.SMA_50 in indicators) SmaState(config.smaPeriod) else null
        fresh.vwap = if (ChartIndicator.VWAP in indicators) VwapState() else null
        fresh.rsi14 = if (ChartIndicator.RSI_14 in indicators) RsiState(config.rsiPeriod) else null
        fresh.macd = if (ChartIndicator.MACD in indicators) {
            MacdState(config.macdFast, config.macdSlow, config.macdSignal)
        } else null
        fresh.bollinger = if (ChartIndicator.BOLLINGER in indicators) {
            BollingerState(config.bollingerPeriod, config.bollingerMultiplier)
        } else null
        fresh.supertrend = if (ChartIndicator.SUPERTREND in indicators) {
            SupertrendState(config.supertrendPeriod, config.supertrendMultiplier)
        } else null

        var beforeLast = fresh.copy()
        for (index in candles.indices) {
            if (index == candles.size - 1) beforeLast = fresh.copy()
            feedLast(candles[index], index, fresh, config)
        }

        this.candles = ArrayList(candles)
        this.indicatorKey = indicators.toSet()
        this.timeframeKey = timeframeKey
        this.activeConfig = config
        this.result = full
        this.states = fresh
        this.beforeLastStates = beforeLast
        return full
    }

    private fun feedLast(
        candle: ChartCandle,
        index: Int,
        target: States,
        config: IndicatorConfig
    ): LastValues {
        val indicators = config.enabled
        val close = config.source.value(candle)
        val ema = if (ChartIndicator.EMA_20 in indicators) {
            target.ema20?.feed(close) ?: Double.NaN
        } else Double.NaN
        val sma = if (ChartIndicator.SMA_50 in indicators) {
            target.sma50?.feed(close) ?: Double.NaN
        } else Double.NaN
        val vwap = if (ChartIndicator.VWAP in indicators) {
            target.vwap?.feed(candle, index) ?: Double.NaN
        } else Double.NaN
        val rsi = if (ChartIndicator.RSI_14 in indicators) {
            target.rsi14?.feed(close) ?: Double.NaN
        } else Double.NaN
        val macdState = if (ChartIndicator.MACD in indicators) target.macd else null
        val macd = macdState?.feed(close) ?: Triple(Double.NaN, Double.NaN, Double.NaN)
        val bollingerState = if (ChartIndicator.BOLLINGER in indicators) target.bollinger else null
        val bollinger = bollingerState?.feed(close)
            ?: Triple(Double.NaN, Double.NaN, Double.NaN)
        val supertrendState = if (ChartIndicator.SUPERTREND in indicators) target.supertrend else null
        val atr = supertrendState?.atr?.feed(candle) ?: Double.NaN
        val supertrend = supertrendState?.feed(candle, atr, index)
            ?: Pair(Double.NaN, 0)

        return LastValues(
            ema20 = ema,
            sma50 = sma,
            vwap = vwap,
            rsi14 = rsi,
            macd = macd.first,
            signal = macd.second,
            histogram = macd.third,
            bollingerMiddle = bollinger.first,
            bollingerUpper = bollinger.second,
            bollingerLower = bollinger.third,
            supertrendLine = supertrend.first,
            supertrendDirection = supertrend.second
        )
    }

    private fun copyAndSet(source: DoubleArray, index: Int, value: Double, grow: Boolean): DoubleArray {
        if (source.isEmpty()) return DoubleArray(0)
        val target = source.copyOf(if (grow) index + 1 else source.size)
        target[index] = value
        return target
    }

    private fun samePrefix(a: List<ChartCandle>, b: List<ChartCandle>, count: Int): Boolean {
        for (index in 0 until count) {
            if (a[index] != b[index]) return false
        }
        return true
    }

    private fun hasCompleteStates(target: States, indicators: Set<ChartIndicator>): Boolean {
        return (ChartIndicator.EMA_20 !in indicators || target.ema20 != null) &&
            (ChartIndicator.SMA_50 !in indicators || target.sma50 != null) &&
            (ChartIndicator.VWAP !in indicators || target.vwap != null) &&
            (ChartIndicator.RSI_14 !in indicators || target.rsi14 != null) &&
            (ChartIndicator.MACD !in indicators || target.macd != null) &&
            (ChartIndicator.BOLLINGER !in indicators || target.bollinger != null) &&
            (ChartIndicator.SUPERTREND !in indicators || target.supertrend != null)
    }

    private fun hasCompleteResult(size: Int, indicators: Set<ChartIndicator>): Boolean {
        if (ChartIndicator.EMA_20 in indicators && result.ema20.size != size) return false
        if (ChartIndicator.SMA_50 in indicators && result.sma50.size != size) return false
        if (ChartIndicator.VWAP in indicators && result.vwap.size != size) return false
        if (ChartIndicator.RSI_14 in indicators && result.rsi14.size != size) return false
        if (ChartIndicator.MACD in indicators) {
            val value = result.macd ?: return false
            if (value.macd.size != size || value.signal.size != size ||
                value.histogram.size != size
            ) return false
        }
        if (ChartIndicator.BOLLINGER in indicators) {
            val value = result.bollinger ?: return false
            if (value.middle.size != size || value.upper.size != size ||
                value.lower.size != size
            ) return false
        }
        if (ChartIndicator.SUPERTREND in indicators) {
            val value = result.supertrend ?: return false
            if (value.line.size != size || value.direction.size != size) return false
        }
        return true
    }

    private fun emptyResult(): Result = Result(
        ema20 = DoubleArray(0),
        sma50 = DoubleArray(0),
        vwap = DoubleArray(0),
        rsi14 = DoubleArray(0),
        macd = null,
        bollinger = null,
        supertrend = null
    )

    private class States {
        var ema20: EmaState? = null
        var sma50: SmaState? = null
        var vwap: VwapState? = null
        var rsi14: RsiState? = null
        var macd: MacdState? = null
        var bollinger: BollingerState? = null
        var supertrend: SupertrendState? = null

        fun copy(): States = States().also {
            it.ema20 = ema20?.copy()
            it.sma50 = sma50?.copy()
            it.vwap = vwap?.copy()
            it.rsi14 = rsi14?.copy()
            it.macd = macd?.copy()
            it.bollinger = bollinger?.copy()
            it.supertrend = supertrend?.copy()
        }
    }

    private class EmaState(val period: Int) {
        private val multiplier = 2.0 / (period + 1.0)
        private var seed = 0.0
        private var seedCount = 0
        private var previous = Double.NaN

        fun feed(value: Double): Double {
            if (!value.isFinite()) {
                seed = 0.0
                seedCount = 0
                previous = Double.NaN
                return Double.NaN
            }
            if (!previous.isFinite()) {
                seed += value
                seedCount++
                if (seedCount == period) {
                    previous = seed / seedCount
                    return previous
                }
                return Double.NaN
            }
            previous = (value - previous) * multiplier + previous
            return previous
        }

        fun copy(): EmaState = EmaState(period).also {
            it.seed = seed
            it.seedCount = seedCount
            it.previous = previous
        }

        fun copyFrom(other: EmaState) {
            seed = other.seed
            seedCount = other.seedCount
            previous = other.previous
        }
    }

    private class SmaState(val period: Int) {
        private var sum = 0.0
        private var validCount = 0
        private val window = ArrayDeque<Double>()

        fun feed(value: Double): Double {
            if (!value.isFinite()) {
                sum = 0.0
                validCount = 0
                window.clear()
                return Double.NaN
            }
            sum += value
            validCount++
            window.addLast(value)
            if (validCount > period) {
                sum -= window.removeFirst()
            }
            return if (validCount >= period) sum / period else Double.NaN
        }

        fun copy(): SmaState = SmaState(period).also {
            it.sum = sum
            it.validCount = validCount
            it.window.addAll(window)
        }

        fun copyFrom(other: SmaState) {
            sum = other.sum
            validCount = other.validCount
            window.clear()
            window.addAll(other.window)
        }
    }

    private class VwapState {
        private var activeSession = ""
        private var cumulativePriceVolume = 0.0
        private var cumulativeVolume = 0.0

        fun feed(candle: ChartCandle, index: Int): Double {
            val session = if (candle.timestamp > 0L) {
                TimeframeBuckets.sessionKey(candle.timestamp)
            } else {
                ""
            }
            if (index == 0 || session != activeSession) {
                activeSession = session
                cumulativePriceVolume = 0.0
                cumulativeVolume = 0.0
            }
            if (!candle.high.isFinite() || !candle.low.isFinite() ||
                !candle.close.isFinite() || candle.volume < 0L
            ) return Double.NaN
            if (candle.high < candle.low) return Double.NaN
            val typical = (candle.high / 3.0) + (candle.low / 3.0) + (candle.close / 3.0)
            val volume = candle.volume.toDouble()
            val priceVolume = typical * volume
            if (!typical.isFinite() || !priceVolume.isFinite()) return Double.NaN
            val nextPriceVolume = cumulativePriceVolume + priceVolume
            val nextVolume = cumulativeVolume + volume
            if (!nextPriceVolume.isFinite() || !nextVolume.isFinite()) return Double.NaN
            cumulativePriceVolume = nextPriceVolume
            cumulativeVolume = nextVolume
            if (cumulativeVolume <= 0.0) return Double.NaN
            val value = cumulativePriceVolume / cumulativeVolume
            return if (value.isFinite()) value else Double.NaN
        }

        fun copy(): VwapState = VwapState().also {
            it.activeSession = activeSession
            it.cumulativePriceVolume = cumulativePriceVolume
            it.cumulativeVolume = cumulativeVolume
        }
    }

    private class RsiState(private val period: Int) {
        private var previous = Double.NaN
        private var seedGain = 0.0
        private var seedLoss = 0.0
        private var seedCount = 0
        private var averageGain = Double.NaN
        private var averageLoss = Double.NaN

        fun feed(value: Double): Double {
            val current = value
            val prior = previous
            previous = current
            if (!current.isFinite() || !prior.isFinite()) {
                seedGain = 0.0
                seedLoss = 0.0
                seedCount = 0
                averageGain = Double.NaN
                averageLoss = Double.NaN
                return Double.NaN
            }
            val change = current - prior
            val gain = if (change > 0.0) change else 0.0
            val loss = if (change < 0.0) -change else 0.0
            if (!averageGain.isFinite()) {
                seedGain += gain
                seedLoss += loss
                seedCount++
                if (seedCount == period) {
                    averageGain = seedGain / period
                    averageLoss = seedLoss / period
                    return rsiValue(averageGain, averageLoss)
                }
                return Double.NaN
            }
            averageGain = (averageGain * (period - 1) + gain) / period
            averageLoss = (averageLoss * (period - 1) + loss) / period
            return rsiValue(averageGain, averageLoss)
        }

        fun copy(): RsiState = RsiState(period).also {
            it.previous = previous
            it.seedGain = seedGain
            it.seedLoss = seedLoss
            it.seedCount = seedCount
            it.averageGain = averageGain
            it.averageLoss = averageLoss
        }

        private fun rsiValue(gain: Double, loss: Double): Double {
            if (gain == 0.0 && loss == 0.0) return 50.0
            if (loss == 0.0) return 100.0
            val relativeStrength = gain / loss
            return 100.0 - (100.0 / (1.0 + relativeStrength))
        }
    }

    private class MacdState(fastPeriod: Int, slowPeriod: Int, signalPeriod: Int) {
        private val fast = EmaState(fastPeriod)
        private val slow = EmaState(slowPeriod)
        private val signal = EmaState(signalPeriod)
        private var firstMacdFound = false

        fun feed(value: Double): Triple<Double, Double, Double> {
            val fastValue = fast.feed(value)
            val slowValue = slow.feed(value)
            val macd = if (fastValue.isFinite() && slowValue.isFinite()) {
                fastValue - slowValue
            } else {
                Double.NaN
            }
            if (!firstMacdFound) {
                if (!macd.isFinite()) return Triple(macd, Double.NaN, Double.NaN)
                firstMacdFound = true
            }
            val signalValue = signal.feed(macd)
            val histogram = if (macd.isFinite() && signalValue.isFinite()) {
                macd - signalValue
            } else {
                Double.NaN
            }
            return Triple(macd, signalValue, histogram)
        }

        fun copy(): MacdState = MacdState(fast.period, slow.period, signal.period).also {
            it.fast.copyFrom(fast)
            it.slow.copyFrom(slow)
            it.signal.copyFrom(signal)
            it.firstMacdFound = firstMacdFound
        }
    }

    private class BollingerState(private val period: Int, private val multiplier: Double) {
        private val average = SmaState(period)
        private val window = ArrayDeque<Double>()

        fun feed(value: Double): Triple<Double, Double, Double> {
            val middle = average.feed(value)
            window.addLast(value)
            if (window.size > period) window.removeFirst()
            if (!middle.isFinite() || window.size < period ||
                !multiplier.isFinite() || multiplier < 0.0 || period <= 1
            ) {
                return Triple(middle, Double.NaN, Double.NaN)
            }
            var sum = 0.0
            for (item in window) {
                if (!item.isFinite()) return Triple(middle, Double.NaN, Double.NaN)
                val diff = item - middle
                sum += diff * diff
            }
            val deviation = sqrt(sum / period)
            return Triple(
                middle,
                middle + multiplier * deviation,
                middle - multiplier * deviation
            )
        }

        fun copy(): BollingerState = BollingerState(period, multiplier).also {
            it.average.copyFrom(average)
            it.window.addAll(window)
        }
    }

    private class SupertrendState(private val period: Int, private val multiplier: Double) {
        val atr = AtrState(period)
        private var finalUpper = Double.NaN
        private var finalLower = Double.NaN
        private var previousDirection = 1
        private var previousArrayDirection = 0
        private var previousClose = Double.NaN
        private var index = 0

        fun feed(candle: ChartCandle, currentAtr: Double, currentIndex: Int): Pair<Double, Int> {
            if (!currentAtr.isFinite()) {
                finalUpper = Double.NaN
                finalLower = Double.NaN
                previousDirection = 1
                previousArrayDirection = 0
                previousClose = candle.close
                index = currentIndex + 1
                return Pair(Double.NaN, 0)
            }
            val middle = candle.high / 2.0 + candle.low / 2.0
            val basicUpper = middle + multiplier * currentAtr
            val basicLower = middle - multiplier * currentAtr
            val priorClose = if (index > 0) previousClose else candle.close
            val upper = if (
                !finalUpper.isFinite() ||
                basicUpper < finalUpper ||
                priorClose > finalUpper
            ) basicUpper else finalUpper
            val lower = if (
                !finalLower.isFinite() ||
                basicLower > finalLower ||
                priorClose < finalLower
            ) basicLower else finalLower
            val direction = when {
                index == 0 || previousArrayDirection == 0 -> 1
                previousDirection == -1 &&
                    candle.close > (if (finalUpper.isFinite()) finalUpper else upper) -> 1
                previousDirection == 1 &&
                    candle.close < (if (finalLower.isFinite()) finalLower else lower) -> -1
                else -> previousDirection
            }
            finalUpper = upper
            finalLower = lower
            previousDirection = direction
            previousArrayDirection = direction
            previousClose = candle.close
            index = currentIndex + 1
            return Pair(if (direction == 1) lower else upper, direction)
        }

        fun copy(): SupertrendState = SupertrendState(period, multiplier).also {
            it.atr.copyFrom(atr)
            it.finalUpper = finalUpper
            it.finalLower = finalLower
            it.previousDirection = previousDirection
            it.previousArrayDirection = previousArrayDirection
            it.previousClose = previousClose
            it.index = index
        }
    }

    private class AtrState(private val period: Int) {
        private var previousAtr = Double.NaN
        private var seedSum = 0.0
        private var validCount = 0
        private var previousClose = Double.NaN

        fun feed(candle: ChartCandle): Double {
            val valid = candle.high.isFinite() && candle.low.isFinite() &&
                candle.close.isFinite() && candle.high >= candle.low
            if (!valid) {
                reset()
                return Double.NaN
            }
            val trueRange = if (previousClose.isFinite()) {
                max(
                    candle.high - candle.low,
                    max(abs(candle.high - previousClose), abs(candle.low - previousClose))
                )
            } else {
                candle.high - candle.low
            }
            if (!trueRange.isFinite() || trueRange < 0.0) {
                reset()
                return Double.NaN
            }
            previousClose = candle.close
            if (!previousAtr.isFinite()) {
                seedSum += trueRange
                validCount++
                if (validCount == period) {
                    previousAtr = seedSum / period
                    return previousAtr
                }
                return Double.NaN
            }
            previousAtr = (previousAtr * (period - 1) + trueRange) / period
            return previousAtr
        }

        fun copy(): AtrState = AtrState(period).also {
            it.previousAtr = previousAtr
            it.seedSum = seedSum
            it.validCount = validCount
            it.previousClose = previousClose
        }

        fun copyFrom(other: AtrState) {
            previousAtr = other.previousAtr
            seedSum = other.seedSum
            validCount = other.validCount
            previousClose = other.previousClose
        }

        private fun reset() {
            previousAtr = Double.NaN
            seedSum = 0.0
            validCount = 0
            previousClose = Double.NaN
        }
    }

    private companion object {
        const val EMA_PERIOD = 20
        const val SMA_PERIOD = 50
        const val RSI_PERIOD = 14
        const val MACD_FAST = 12
        const val MACD_SLOW = 26
        const val MACD_SIGNAL = 9
        const val BOLLINGER_PERIOD = 20
        const val BOLLINGER_MULTIPLIER = 2.0
        const val SUPERTREND_PERIOD = 10
        const val SUPERTREND_MULTIPLIER = 3.0
    }
}
