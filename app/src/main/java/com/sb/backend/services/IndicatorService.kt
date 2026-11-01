package com.sb.backend.services

import com.sb.backend.models.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object IndicatorService {

    // ------------------------------------------------------------
    // INPUT VALIDATION
    // ------------------------------------------------------------

    private fun validateValues(values: List<Double>) {
        require(values.all { it.isFinite() }) {
            "Indicator input contains invalid numeric values."
        }
    }

    private fun validateCandles(candles: List<Candle>) {
        candles.forEachIndexed { index, candle ->
            require(
                candle.open.isFinite() &&
                    candle.high.isFinite() &&
                    candle.low.isFinite() &&
                    candle.close.isFinite()
            ) {
                "Invalid OHLC value at candle index $index."
            }

            require(
                candle.open > 0.0 &&
                    candle.high > 0.0 &&
                    candle.low > 0.0 &&
                    candle.close > 0.0
            ) {
                "Candle prices must be positive at index $index."
            }

            require(
                candle.high >= max(candle.open, candle.close) &&
                    candle.low <= min(candle.open, candle.close) &&
                    candle.high >= candle.low
            ) {
                "Invalid candle OHLC range at index $index."
            }
        }
    }

    // ------------------------------------------------------------
    // SIMPLE MOVING AVERAGE
    // ------------------------------------------------------------

    fun sma(
        values: List<Double>,
        period: Int
    ): List<Double?> {
        require(period > 0) {
            "Period must be positive."
        }

        validateValues(values)

        val result = ArrayList<Double?>(values.size)
        var sum = 0.0

        for (index in values.indices) {
            sum += values[index]

            if (index >= period) {
                sum -= values[index - period]
            }

            result.add(
                if (index >= period - 1) {
                    sum / period
                } else {
                    null
                }
            )
        }

        return result
    }

    // ------------------------------------------------------------
    // EXPONENTIAL MOVING AVERAGE
    // ------------------------------------------------------------

    fun ema(
        values: List<Double>,
        period: Int
    ): List<Double?> {
        require(period > 0) {
            "Period must be positive."
        }

        validateValues(values)

        val result = ArrayList<Double?>(values.size)
        val multiplier = 2.0 / (period + 1.0)

        var previous: Double? = null
        var seedSum = 0.0

        for (index in values.indices) {
            val value = values[index]

            if (index < period - 1) {
                seedSum += value
                result.add(null)

            } else if (index == period - 1) {
                seedSum += value

                previous = seedSum / period
                result.add(previous)

            } else {
                val current =
                    value * multiplier +
                        (previous ?: value) * (1.0 - multiplier)

                previous = current
                result.add(current)
            }
        }

        return result
    }

    // ------------------------------------------------------------
    // RELATIVE STRENGTH INDEX
    // Wilder RSI
    // ------------------------------------------------------------

    fun rsi(
        values: List<Double>,
        period: Int = 14
    ): List<Double?> {
        require(period > 0) {
            "Period must be positive."
        }

        validateValues(values)

        if (values.isEmpty()) {
            return emptyList()
        }

        val result = MutableList<Double?>(values.size) {
            null
        }

        if (values.size <= period) {
            return result
        }

        var gainSum = 0.0
        var lossSum = 0.0

        for (index in 1..period) {
            val change = values[index] - values[index - 1]

            if (change > 0.0) {
                gainSum += change
            } else if (change < 0.0) {
                lossSum += abs(change)
            }
        }

        var averageGain = gainSum / period
        var averageLoss = lossSum / period

        result[period] = rsiValue(
            averageGain,
            averageLoss
        )

        for (index in period + 1 until values.size) {
            val change = values[index] - values[index - 1]

            val gain = if (change > 0.0) {
                change
            } else {
                0.0
            }

            val loss = if (change < 0.0) {
                abs(change)
            } else {
                0.0
            }

            averageGain =
                (averageGain * (period - 1) + gain) / period

            averageLoss =
                (averageLoss * (period - 1) + loss) / period

            result[index] = rsiValue(
                averageGain,
                averageLoss
            )
        }

        return result
    }

    // ------------------------------------------------------------
    // AVERAGE TRUE RANGE
    // Wilder ATR
    // ------------------------------------------------------------

    fun atr(
        candles: List<Candle>,
        period: Int = 14
    ): List<Double?> {
        require(period > 0) {
            "Period must be positive."
        }

        validateCandles(candles)

        val result = ArrayList<Double?>(candles.size)

        if (candles.isEmpty()) {
            return result
        }

        var previousAtr: Double? = null
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

            if (index < period - 1) {
                seedSum += trueRange
                result.add(null)

            } else if (index == period - 1) {
                seedSum += trueRange

                previousAtr = seedSum / period
                result.add(previousAtr)

            } else {
                val current =
                    (
                        (previousAtr ?: trueRange) * (period - 1) +
                            trueRange
                        ) / period

                previousAtr = current
                result.add(current)
            }
        }

        return result
    }

    // ------------------------------------------------------------
    // HEIKIN ASHI
    // ------------------------------------------------------------

    fun heikinAshi(
        source: List<Candle>
    ): List<Candle> {
        validateCandles(source)

        val result = ArrayList<Candle>(source.size)

        if (source.isEmpty()) {
            return result
        }

        var previousHaOpen =
            (source[0].open + source[0].close) / 2.0

        var previousHaClose = (
            source[0].open +
                source[0].high +
                source[0].low +
                source[0].close
            ) / 4.0

        for (index in source.indices) {
            val candle = source[index]

            val haClose = (
                candle.open +
                    candle.high +
                    candle.low +
                    candle.close
                ) / 4.0

            val haOpen = if (index == 0) {
                (candle.open + candle.close) / 2.0
            } else {
                (previousHaOpen + previousHaClose) / 2.0
            }

            val haHigh = max(
                candle.high,
                max(haOpen, haClose)
            )

            val haLow = min(
                candle.low,
                min(haOpen, haClose)
            )

            result.add(
                Candle(
                    symbol = candle.symbol,
                    timeframe = candle.timeframe,
                    timestamp = candle.timestamp,
                    open = haOpen,
                    high = haHigh,
                    low = haLow,
                    close = haClose,
                    volume = candle.volume
                )
            )

            previousHaOpen = haOpen
            previousHaClose = haClose
        }

        return result
    }

    // ------------------------------------------------------------
    // SUPPORT AND RESISTANCE
    // ------------------------------------------------------------

    fun supportResistance(
        candles: List<Candle>,
        lookback: Int = 50
    ): Pair<Double, Double>? {
        if (candles.isEmpty()) {
            return null
        }

        require(lookback > 0) {
            "Lookback must be positive."
        }

        validateCandles(candles)

        val safeLookback = lookback.coerceAtMost(candles.size)
        val start = candles.size - safeLookback

        var support = Double.MAX_VALUE
        var resistance = -Double.MAX_VALUE

        for (index in start until candles.size) {
            support = min(
                support,
                candles[index].low
            )

            resistance = max(
                resistance,
                candles[index].high
            )
        }

        if (!support.isFinite() || !resistance.isFinite()) {
            return null
        }

        return support to resistance
    }

    // ------------------------------------------------------------
    // MOVING AVERAGE CROSSOVER
    // ------------------------------------------------------------

    fun crossover(
        fast: List<Double?>,
        slow: List<Double?>,
        index: Int
    ): Int {
        if (index <= 0 ||
            index >= fast.size ||
            index >= slow.size
        ) {
            return 0
        }

        val currentFast = fast[index] ?: return 0
        val currentSlow = slow[index] ?: return 0

        val previousFast = fast[index - 1] ?: return 0
        val previousSlow = slow[index - 1] ?: return 0

        if (!currentFast.isFinite() ||
            !currentSlow.isFinite() ||
            !previousFast.isFinite() ||
            !previousSlow.isFinite()
        ) {
            return 0
        }

        return when {
            previousFast <= previousSlow &&
                currentFast > currentSlow -> 1

            previousFast >= previousSlow &&
                currentFast < currentSlow -> -1

            else -> 0
        }
    }

    // ------------------------------------------------------------
    // LAST VALID INDICATOR VALUE
    // ------------------------------------------------------------

    fun lastValue(
        values: List<Double?>
    ): Double? {
        for (index in values.indices.reversed()) {
            val value = values[index]

            if (value != null && value.isFinite()) {
                return value
            }
        }

        return null
    }

    // ------------------------------------------------------------
    // RSI CALCULATION HELPER
    // ------------------------------------------------------------

    private fun rsiValue(
        gain: Double,
        loss: Double
    ): Double {
        // No price movement: neutral RSI.
        if (gain <= 0.0 && loss <= 0.0) {
            return 50.0
        }

        // Only upward movement.
        if (loss <= 0.0) {
            return 100.0
        }

        // Only downward movement.
        if (gain <= 0.0) {
            return 0.0
        }

        val relativeStrength = gain / loss

        return 100.0 - (
            100.0 / (1.0 + relativeStrength)
            )
    }

    // ------------------------------------------------------------
    // MACD
    // Default: 12, 26, 9
    // ------------------------------------------------------------

    data class MacdResult(
        val macdLine: List<Double?>,
        val signalLine: List<Double?>,
        val histogram: List<Double?>
    )

    fun macd(
        values: List<Double>,
        fastPeriod: Int = 12,
        slowPeriod: Int = 26,
        signalPeriod: Int = 9
    ): MacdResult {
        require(fastPeriod > 0 && slowPeriod > fastPeriod && signalPeriod > 0) {
            "Invalid MACD periods."
        }
        validateValues(values)

        val fast = ema(values, fastPeriod)
        val slow = ema(values, slowPeriod)

        val line = MutableList<Double?>(values.size) { null }
        val validValues = ArrayList<Double>()
        val validIndexes = ArrayList<Int>()

        for (i in values.indices) {
            val f = fast[i]
            val s = slow[i]

            if (f != null && s != null) {
                val value = f - s
                line[i] = value
                validValues.add(value)
                validIndexes.add(i)
            }
        }

        val compressedSignal = ema(validValues, signalPeriod)
        val signal = MutableList<Double?>(values.size) { null }
        val histogram = MutableList<Double?>(values.size) { null }

        for (i in validIndexes.indices) {
            val index = validIndexes[i]
            val signalValue = compressedSignal[i]
            signal[index] = signalValue

            if (signalValue != null) {
                histogram[index] = line[index]?.minus(signalValue)
            }
        }

        return MacdResult(line, signal, histogram)
    }

    // ------------------------------------------------------------
    // STOCHASTIC RSI
    // Default: RSI 14, Stoch 14, K 3, D 3
    // ------------------------------------------------------------

    data class StochRsiResult(
        val stochRsi: List<Double?>,
        val k: List<Double?>,
        val d: List<Double?>
    )

    fun stochRsi(
        values: List<Double>,
        rsiPeriod: Int = 14,
        stochPeriod: Int = 14,
        kPeriod: Int = 3,
        dPeriod: Int = 3
    ): StochRsiResult {
        require(
            rsiPeriod > 0 && stochPeriod > 0 &&
                kPeriod > 0 && dPeriod > 0
        ) {
            "Stoch RSI periods must be positive."
        }

        validateValues(values)

        val rsiValues = rsi(values, rsiPeriod)
        val raw = MutableList<Double?>(values.size) { null }

        for (i in values.indices) {
            if (i < rsiPeriod + stochPeriod - 1) continue

            val window = rsiValues
                .subList(i - stochPeriod + 1, i + 1)
                .filterNotNull()

            if (window.size != stochPeriod) continue

            val lowest = window.min() ?: continue
            val highest = window.max() ?: continue
            val current = rsiValues[i] ?: continue

            raw[i] = if (highest - lowest <= 0.0000001) {
                0.0
            } else {
                ((current - lowest) / (highest - lowest) * 100.0)
                    .coerceIn(0.0, 100.0)
            }
        }

        val k = smaNullable(raw, kPeriod)
        val d = smaNullable(k, dPeriod)

        return StochRsiResult(raw, k, d)
    }

    // ------------------------------------------------------------
    // SUPERTREND
    // Direction: 1 = bullish, -1 = bearish
    // ------------------------------------------------------------

    data class SupertrendResult(
        val line: List<Double?>,
        val direction: List<Int>
    )

    fun supertrend(
        candles: List<Candle>,
        period: Int = 10,
        multiplier: Double = 3.0
    ): SupertrendResult {
        require(period > 0) { "Supertrend period must be positive." }
        require(multiplier.isFinite() && multiplier > 0.0) {
            "Supertrend multiplier must be positive."
        }

        validateCandles(candles)

        val atrValues = atr(candles, period)
        val size = candles.size

        val result = MutableList<Double?>(size) { null }
        val direction = MutableList(size) { 0 }

        var finalUpper: Double? = null
        var finalLower: Double? = null
        var previousDirection = 1

        for (i in candles.indices) {
            val currentAtr = atrValues[i] ?: continue
            val candle = candles[i]
            val middle = (candle.high + candle.low) / 2.0

            val basicUpper = middle + multiplier * currentAtr
            val basicLower = middle - multiplier * currentAtr

            val previousClose = if (i > 0) {
                candles[i - 1].close
            } else {
                candle.close
            }

            val upper = if (
                finalUpper == null ||
                basicUpper < finalUpper ||
                previousClose > finalUpper
            ) {
                basicUpper
            } else {
                finalUpper
            }

            val lower = if (
                finalLower == null ||
                basicLower > finalLower ||
                previousClose < finalLower
            ) {
                basicLower
            } else {
                finalLower
            }

            val currentDirection = when {
                i == 0 || direction[i - 1] == 0 -> 1
                previousDirection == -1 && candle.close > (finalUpper ?: upper) -> 1
                previousDirection == 1 && candle.close < (finalLower ?: lower) -> -1
                else -> previousDirection
            }

            finalUpper = upper
            finalLower = lower
            previousDirection = currentDirection

            direction[i] = currentDirection
            result[i] = if (currentDirection == 1) lower else upper
        }

        return SupertrendResult(result, direction)
    }

    // ------------------------------------------------------------
    // VWAP
    // Cumulative VWAP for the supplied candle sequence.
    // Supply one trading session's candles for session VWAP.
    // ------------------------------------------------------------

    fun vwap(
        candles: List<Candle>
    ): List<Double?> {
        validateCandles(candles)

        val result = ArrayList<Double?>(candles.size)
        var cumulativePriceVolume = 0.0
        var cumulativeVolume = 0.0

        for (candle in candles) {
            val volume = candle.volume.toDouble()
            require(volume.isFinite() && volume >= 0.0) {
                "Invalid candle volume."
            }

            val typicalPrice = (
                candle.high + candle.low + candle.close
            ) / 3.0

            cumulativePriceVolume += typicalPrice * volume
            cumulativeVolume += volume

            result.add(
                if (cumulativeVolume > 0.0) {
                    cumulativePriceVolume / cumulativeVolume
                } else {
                    null
                }
            )
        }

        return result
    }

    // ------------------------------------------------------------
    // SMA FOR NULLABLE INDICATOR SERIES
    // ------------------------------------------------------------

    private fun smaNullable(
        values: List<Double?>,
        period: Int
    ): List<Double?> {
        require(period > 0) { "Period must be positive." }

        val result = MutableList<Double?>(values.size) { null }

        for (i in values.indices) {
            if (i < period - 1) continue

            val window = values.subList(i - period + 1, i + 1)
            if (window.any { it == null || !it.isFinite() }) continue

            result[i] = window.filterNotNull().average()
        }

        return result
    }
}