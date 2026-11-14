package com.sb.ui.chart

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import com.sb.backend.market.TimeframeBuckets

object IndicatorMath {

    fun sma(values: List<Double>, period: Int): DoubleArray {
        val result = DoubleArray(values.size) { Double.NaN }
        if (period <= 0 || values.size < period || values.any { !it.isFinite() }) return result
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
        if (period <= 0 || values.isEmpty() || values.any { !it.isFinite() }) return result
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

    /**
     * Session VWAP using the exchange-local trading date (IST for supported
     * Indian exchanges). Zero-volume candles do not fabricate a VWAP value.
     */
    fun vwap(candles: List<ChartCandle>): DoubleArray {
        val result = DoubleArray(candles.size) { Double.NaN }
        var activeSession = ""
        var cumulativePriceVolume = 0.0
        var cumulativeVolume = 0.0
        for (i in candles.indices) {
            val candle = candles[i]
            val session = if (candle.timestamp > 0L) {
                TimeframeBuckets.sessionKey(candle.timestamp)
            } else {
                ""
            }
            if (i == 0 || session != activeSession) {
                activeSession = session
                cumulativePriceVolume = 0.0
                cumulativeVolume = 0.0
            }
            if (!candle.high.isFinite() || !candle.low.isFinite() ||
                !candle.close.isFinite() || candle.volume < 0L
            ) continue
            if (candle.high < candle.low) continue
            val typical = (candle.high / 3.0) + (candle.low / 3.0) + (candle.close / 3.0)
            val volume = candle.volume.toDouble()
            val priceVolume = typical * volume
            if (!typical.isFinite() || !priceVolume.isFinite()) continue
            val nextPriceVolume = cumulativePriceVolume + priceVolume
            val nextVolume = cumulativeVolume + volume
            if (!nextPriceVolume.isFinite() || !nextVolume.isFinite()) continue
            cumulativePriceVolume = nextPriceVolume
            cumulativeVolume = nextVolume
            if (cumulativeVolume > 0.0) {
                val value = cumulativePriceVolume / cumulativeVolume
                if (value.isFinite()) result[i] = value
            }
        }
        return result
    }

    fun rsi(values: List<Double>, period: Int = 14): DoubleArray {
        val result = DoubleArray(values.size) { Double.NaN }
        if (period <= 0 || values.size <= period || values.any { !it.isFinite() }) return result

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
        if (averageGain == 0.0 && averageLoss == 0.0) return 50.0
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
        val macd = DoubleArray(values.size) { Double.NaN }
        val signal = DoubleArray(values.size) { Double.NaN }
        val histogram = DoubleArray(values.size) { Double.NaN }
        if (fastPeriod <= 0 || slowPeriod <= fastPeriod || signalPeriod <= 0 ||
            values.isEmpty() || values.any { !it.isFinite() }
        ) {
            return MacdResult(macd, signal, histogram)
        }

        val fast = ema(values, fastPeriod)
        val slow = ema(values, slowPeriod)
        for (i in values.indices) {
            if (fast[i].isFinite() && slow[i].isFinite()) {
                macd[i] = fast[i] - slow[i]
            }
        }

        // Seed the signal EMA from the first real MACD value. Replacing
        // pre-warmup NaNs with zero biases the signal line toward zero.
        val firstMacdIndex = macd.indexOfFirst { it.isFinite() }
        if (firstMacdIndex >= 0) {
            val validMacd = macd.copyOfRange(firstMacdIndex, macd.size).toList()
            val alignedSignal = ema(validMacd, signalPeriod)
            for (offset in alignedSignal.indices) {
                val index = firstMacdIndex + offset
                signal[index] = alignedSignal[offset]
                if (macd[index].isFinite() && signal[index].isFinite()) {
                    histogram[index] = macd[index] - signal[index]
                }
            }
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
        if (!multiplier.isFinite() || multiplier < 0.0 || period <= 1) {
            val invalid = DoubleArray(values.size) { Double.NaN }
            return BollingerResult(middle, invalid, invalid.copyOf())
        }
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
        var validCount = 0
        var previousClose = Double.NaN

        for (index in candles.indices) {
            val candle = candles[index]
            val valid = candle.high.isFinite() && candle.low.isFinite() &&
                candle.close.isFinite() && candle.high >= candle.low
            if (!valid) {
                previousAtr = Double.NaN
                seedSum = 0.0
                validCount = 0
                previousClose = Double.NaN
                continue
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
                previousAtr = Double.NaN
                seedSum = 0.0
                validCount = 0
                previousClose = Double.NaN
                continue
            }

            previousClose = candle.close
            if (!previousAtr.isFinite()) {
                seedSum += trueRange
                validCount++
                if (validCount == period) {
                    previousAtr = seedSum / period
                    result[index] = previousAtr
                }
            } else {
                previousAtr = (previousAtr * (period - 1) + trueRange) / period
                result[index] = previousAtr
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
        if (period <= 0 || !multiplier.isFinite() || multiplier <= 0.0 || candles.isEmpty()) {
            return SupertrendResult(line, direction)
        }

        val atrValues = atr(candles, period)
        var finalUpper = Double.NaN
        var finalLower = Double.NaN
        var previousDirection = 1

        for (index in candles.indices) {
            val currentAtr = atrValues[index]
            if (!currentAtr.isFinite()) {
                finalUpper = Double.NaN
                finalLower = Double.NaN
                previousDirection = 1
                continue
            }

            val candle = candles[index]
            val middle = candle.high / 2.0 + candle.low / 2.0
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
