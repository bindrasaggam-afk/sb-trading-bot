package com.sb.ui.chart

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

object IndicatorMath {

    fun sma(values: List<Double>, period: Int): DoubleArray {
        val result = DoubleArray(values.size) { Double.NaN }
        if (period <= 0 || values.size < period) return result
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) {
                sum -= values[i - period]
            }
            if (i >= period - 1) {
                result[i] = sum / period
            }
        }
        return result
    }

    fun ema(values: List<Double>, period: Int): DoubleArray {
        val result = DoubleArray(values.size) { Double.NaN }
        if (period <= 0 || values.isEmpty()) return result
        val multiplier = 2.0 / (period + 1.0)
        var seed = 0.0
        var seedCount = 0
        var previous = Double.NaN
        for (i in values.indices) {
            val value = values[i]
            if (i < period) {
                seed += value
                seedCount++
                if (i == period - 1) {
                    previous = seed / seedCount
                    result[i] = previous
                }
            } else {
                previous = (value - previous) * multiplier + previous
                result[i] = previous
            }
        }
        return result
    }

    fun vwap(candles: List<ChartCandle>): DoubleArray {
        val result = DoubleArray(candles.size) { Double.NaN }
        var cumulativePriceVolume = 0.0
        var cumulativeVolume = 0.0
        for (i in candles.indices) {
            val candle = candles[i]
            val typical = (candle.high + candle.low + candle.close) / 3.0
            val volume = candle.volume.coerceAtLeast(0L).toDouble()
            cumulativePriceVolume += typical * volume
            cumulativeVolume += volume
            result[i] = if (cumulativeVolume > 0.0) {
                cumulativePriceVolume / cumulativeVolume
            } else {
                typical
            }
        }
        return result
    }

    fun rsi(values: List<Double>, period: Int = 14): DoubleArray {
        val result = DoubleArray(values.size) { Double.NaN }
        if (period <= 0 || values.size <= period) return result

        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val change = values[i] - values[i - 1]
            if (change >= 0.0) gain += change else loss -= change
        }
        var averageGain = gain / period
        var averageLoss = loss / period
        result[period] = rsiValue(averageGain, averageLoss)

        for (i in period + 1 until values.size) {
            val change = values[i] - values[i - 1]
            val currentGain = if (change > 0.0) change else 0.0
            val currentLoss = if (change < 0.0) -change else 0.0
            averageGain = (averageGain * (period - 1) + currentGain) / period
            averageLoss = (averageLoss * (period - 1) + currentLoss) / period
            result[i] = rsiValue(averageGain, averageLoss)
        }
        return result
    }

    private fun rsiValue(averageGain: Double, averageLoss: Double): Double {
        if (averageLoss == 0.0) return 100.0
        val relativeStrength = averageGain / averageLoss
        return 100.0 - (100.0 / (1.0 + relativeStrength))
    }

    data class MacdResult(
        val macd: DoubleArray,
        val signal: DoubleArray,
        val histogram: DoubleArray
    )

    fun macd(
        values: List<Double>,
        fastPeriod: Int = 12,
        slowPeriod: Int = 26,
        signalPeriod: Int = 9
    ): MacdResult {
        val fast = ema(values, fastPeriod)
        val slow = ema(values, slowPeriod)
        val macd = DoubleArray(values.size) { i ->
            if (fast[i].isFinite() && slow[i].isFinite()) fast[i] - slow[i] else Double.NaN
        }
        val signal = ema(macd.map { if (it.isFinite()) it else 0.0 }, signalPeriod)
        val histogram = DoubleArray(values.size) { i ->
            if (macd[i].isFinite() && signal[i].isFinite()) macd[i] - signal[i] else Double.NaN
        }
        return MacdResult(macd, signal, histogram)
    }

    data class BollingerResult(
        val middle: DoubleArray,
        val upper: DoubleArray,
        val lower: DoubleArray
    )

    fun bollinger(
        values: List<Double>,
        period: Int = 20,
        multiplier: Double = 2.0
    ): BollingerResult {
        val middle = sma(values, period)
        val deviation = standardDeviation(values, period)
        val upper = DoubleArray(values.size) { index ->
            if (middle[index].isFinite() && deviation[index].isFinite()) {
                middle[index] + multiplier * deviation[index]
            } else {
                Double.NaN
            }
        }
        val lower = DoubleArray(values.size) { index ->
            if (middle[index].isFinite() && deviation[index].isFinite()) {
                middle[index] - multiplier * deviation[index]
            } else {
                Double.NaN
            }
        }
        return BollingerResult(middle, upper, lower)
    }

    fun atr(candles: List<ChartCandle>, period: Int = 14): DoubleArray {
        val result = DoubleArray(candles.size) { Double.NaN }
        if (period <= 0 || candles.isEmpty()) return result

        var previousAtr = Double.NaN
        var seedSum = 0.0

        for (index in candles.indices) {
            val candle = candles[index]
            val trueRange = if (index == 0) {
                candle.high - candle.low
            } else {
                val previousClose = candles[index - 1].close
                max(
                    candle.high - candle.low,
                    max(
                        abs(candle.high - previousClose),
                        abs(candle.low - previousClose)
                    )
                )
            }

            when {
                index < period - 1 -> seedSum += trueRange
                index == period - 1 -> {
                    seedSum += trueRange
                    previousAtr = seedSum / period
                    result[index] = previousAtr
                }
                else -> {
                    val current = (
                        (if (previousAtr.isFinite()) previousAtr else trueRange) *
                            (period - 1) + trueRange
                        ) / period
                    previousAtr = current
                    result[index] = current
                }
            }
        }
        return result
    }

    data class SupertrendResult(
        val line: DoubleArray,
        val direction: IntArray
    )

    fun supertrend(
        candles: List<ChartCandle>,
        period: Int = 10,
        multiplier: Double = 3.0
    ): SupertrendResult {
        val line = DoubleArray(candles.size) { Double.NaN }
        val direction = IntArray(candles.size)
        if (period <= 0 || multiplier <= 0.0 || candles.isEmpty()) {
            return SupertrendResult(line, direction)
        }

        val atrValues = atr(candles, period)
        var finalUpper = Double.NaN
        var finalLower = Double.NaN
        var previousDirection = 1

        for (index in candles.indices) {
            val currentAtr = atrValues[index]
            if (!currentAtr.isFinite()) continue

            val candle = candles[index]
            val middle = (candle.high + candle.low) / 2.0
            val basicUpper = middle + multiplier * currentAtr
            val basicLower = middle - multiplier * currentAtr
            val previousClose = if (index > 0) {
                candles[index - 1].close
            } else {
                candle.close
            }

            val upper = if (
                !finalUpper.isFinite() ||
                basicUpper < finalUpper ||
                previousClose > finalUpper
            ) {
                basicUpper
            } else {
                finalUpper
            }

            val lower = if (
                !finalLower.isFinite() ||
                basicLower > finalLower ||
                previousClose < finalLower
            ) {
                basicLower
            } else {
                finalLower
            }

            val currentDirection = when {
                index == 0 || direction[index - 1] == 0 -> 1
                previousDirection == -1 &&
                    candle.close > (if (finalUpper.isFinite()) finalUpper else upper) -> 1
                previousDirection == 1 &&
                    candle.close < (if (finalLower.isFinite()) finalLower else lower) -> -1
                else -> previousDirection
            }

            finalUpper = upper
            finalLower = lower
            previousDirection = currentDirection
            direction[index] = currentDirection
            line[index] = if (currentDirection == 1) lower else upper
        }

        return SupertrendResult(line, direction)
    }

    fun standardDeviation(values: List<Double>, period: Int): DoubleArray {
        val result = DoubleArray(values.size) { Double.NaN }
        if (period <= 1) return result
        val averages = sma(values, period)
        for (i in period - 1 until values.size) {
            val mean = averages[i]
            if (!mean.isFinite()) continue
            var sum = 0.0
            for (j in i - period + 1..i) {
                val diff = values[j] - mean
                sum += diff * diff
            }
            result[i] = sqrt(sum / period)
        }
        return result
    }
}
