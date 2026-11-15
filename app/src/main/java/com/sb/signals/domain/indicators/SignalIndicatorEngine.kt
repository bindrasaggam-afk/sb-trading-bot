package com.sb.signals.domain.indicators

import com.sb.backend.market.TimeframeBuckets
import com.sb.backend.market.model.MarketCandle
import com.sb.backend.models.Candle
import com.sb.backend.services.IndicatorService
import com.sb.signals.domain.StrategyConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class AdxResult(
    val adx: List<Double?>,
    val plusDi: List<Double?>,
    val minusDi: List<Double?>
)

data class BollingerResult(
    val middle: List<Double?>,
    val upper: List<Double?>,
    val lower: List<Double?>
)

data class IndicatorComputation(
    val values: Map<String, Double?>,
    val series: Map<String, List<Double?>>
) {
    fun latest(key: String): Double? = values[key]
}

object SignalIndicatorEngine {

    fun compute(
        candles: List<MarketCandle>,
        config: StrategyConfig
    ): IndicatorComputation {
        if (candles.isEmpty()) {
            return IndicatorComputation(emptyMap(), emptyMap())
        }

        val closes = candles.map { it.close }
        val bridged = candles.map { candle ->
            Candle(
                symbol = "SIGNAL",
                timeframe = "5m",
                timestamp = candle.timestamp,
                open = candle.open,
                high = candle.high,
                low = candle.low,
                close = candle.close,
                volume = candle.volume,
                isComplete = true
            )
        }

        val emaFast = IndicatorService.ema(closes, config.emaFast)
        val emaSlow = IndicatorService.ema(closes, config.emaSlow)
        val emaTrend = IndicatorService.ema(closes, config.emaTrend)
        val rsi = IndicatorService.rsi(closes, config.rsiPeriod)
        val macd = IndicatorService.macd(closes)
        val atr = IndicatorService.atr(bridged, config.atrPeriod)
        val supertrend = IndicatorService.supertrend(
            candles = bridged,
            period = 10,
            multiplier = 3.0
        )
        val adx = adx(bridged, config.adxPeriod)
        val bollinger = bollinger(closes, 20, 2.0)
        val vwap = sessionVwap(candles)
        val volumeMa = volumeSma(candles, config.volumePeriod)

        val last = candles.lastIndex
        val series = linkedMapOf(
            "EMA${config.emaFast}" to emaFast,
            "EMA${config.emaSlow}" to emaSlow,
            "EMA${config.emaTrend}" to emaTrend,
            "RSI${config.rsiPeriod}" to rsi,
            "MACD" to macd.macdLine,
            "MACDSignal" to macd.signalLine,
            "MACDHistogram" to macd.histogram,
            "ATR${config.atrPeriod}" to atr,
            "Supertrend" to supertrend.line,
            "ADX${config.adxPeriod}" to adx.adx,
            "PlusDI" to adx.plusDi,
            "MinusDI" to adx.minusDi,
            "VWAP" to vwap,
            "VolumeMA${config.volumePeriod}" to volumeMa,
            "BBMiddle" to bollinger.middle,
            "BBUpper" to bollinger.upper,
            "BBLower" to bollinger.lower
        )

        val values = linkedMapOf<String, Double?>(
            "Close" to candles[last].close,
            "Open" to candles[last].open,
            "High" to candles[last].high,
            "Low" to candles[last].low,
            "Volume" to candles[last].volume.toDouble()
        )
        series.forEach { (key, valuesAt) ->
            values[key] = valuesAt.getOrNull(last)
        }
        values["SupertrendDirection"] =
            supertrend.direction.getOrNull(last)?.toDouble()

        return IndicatorComputation(values, series)
    }

    fun adx(
        candles: List<Candle>,
        period: Int = 14
    ): AdxResult {
        require(period > 1)
        if (candles.isEmpty()) {
            return AdxResult(emptyList(), emptyList(), emptyList())
        }

        val size = candles.size
        val adxValues = MutableList<Double?>(size) { null }
        val plusDiValues = MutableList<Double?>(size) { null }
        val minusDiValues = MutableList<Double?>(size) { null }

        if (size <= period) {
            return AdxResult(adxValues, plusDiValues, minusDiValues)
        }

        val trueRanges = DoubleArray(size)
        val plusDm = DoubleArray(size)
        val minusDm = DoubleArray(size)

        for (index in 1 until size) {
            val current = candles[index]
            val previous = candles[index - 1]
            val upMove = current.high - previous.high
            val downMove = previous.low - current.low

            plusDm[index] = if (upMove > downMove && upMove > 0.0) upMove else 0.0
            minusDm[index] = if (downMove > upMove && downMove > 0.0) downMove else 0.0
            trueRanges[index] = max(
                current.high - current.low,
                max(
                    abs(current.high - previous.close),
                    abs(current.low - previous.close)
                )
            )
        }

        var trSum = 0.0
        var plusSum = 0.0
        var minusSum = 0.0
        for (index in 1..period) {
            trSum += trueRanges[index]
            plusSum += plusDm[index]
            minusSum += minusDm[index]
        }

        var dxSum = 0.0
        var dxCount = 0
        var currentAdx: Double? = null

        for (index in period until size) {
            if (index > period) {
                trSum = trSum - (trSum / period) + trueRanges[index]
                plusSum = plusSum - (plusSum / period) + plusDm[index]
                minusSum = minusSum - (minusSum / period) + minusDm[index]
            }

            val plusDi = if (trSum > 0.0) 100.0 * plusSum / trSum else 0.0
            val minusDi = if (trSum > 0.0) 100.0 * minusSum / trSum else 0.0
            plusDiValues[index] = plusDi
            minusDiValues[index] = minusDi

            val diSum = plusDi + minusDi
            val dx = if (diSum > 0.0) {
                100.0 * abs(plusDi - minusDi) / diSum
            } else {
                0.0
            }

            if (index < period * 2 - 1) {
                dxSum += dx
                dxCount++
            } else if (index == period * 2 - 1) {
                dxSum += dx
                dxCount++
                currentAdx = dxSum / dxCount
                adxValues[index] = currentAdx
            } else {
                val previousAdx = currentAdx ?: dx
                currentAdx = ((previousAdx * (period - 1)) + dx) / period
                adxValues[index] = currentAdx
            }
        }

        return AdxResult(adxValues, plusDiValues, minusDiValues)
    }

    fun bollinger(
        values: List<Double>,
        period: Int = 20,
        multiplier: Double = 2.0
    ): BollingerResult {
        require(period > 1)
        require(multiplier > 0.0)

        val middle = MutableList<Double?>(values.size) { null }
        val upper = MutableList<Double?>(values.size) { null }
        val lower = MutableList<Double?>(values.size) { null }

        for (index in values.indices) {
            if (index < period - 1) continue
            val start = index - period + 1
            var sum = 0.0
            for (i in start..index) sum += values[i]
            val mean = sum / period

            var variance = 0.0
            for (i in start..index) {
                val difference = values[i] - mean
                variance += difference * difference
            }
            val deviation = sqrt(variance / period)

            middle[index] = mean
            upper[index] = mean + multiplier * deviation
            lower[index] = mean - multiplier * deviation
        }

        return BollingerResult(middle, upper, lower)
    }

    fun sessionVwap(candles: List<MarketCandle>): List<Double?> {
        val result = ArrayList<Double?>(candles.size)
        var cumulativePriceVolume = 0.0
        var cumulativeVolume = 0.0
        var session = ""

        for (candle in candles) {
            val candleSession = TimeframeBuckets.sessionKey(candle.timestamp)
            if (candleSession != session) {
                session = candleSession
                cumulativePriceVolume = 0.0
                cumulativeVolume = 0.0
            }

            val typicalPrice =
                (candle.high + candle.low + candle.close) / 3.0
            cumulativePriceVolume += typicalPrice * candle.volume
            cumulativeVolume += candle.volume

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

    fun volumeSma(
        candles: List<MarketCandle>,
        period: Int
    ): List<Double?> {
        require(period > 0)
        val result = MutableList<Double?>(candles.size) { null }
        var sum = 0.0

        for (index in candles.indices) {
            sum += candles[index].volume
            if (index >= period) {
                sum -= candles[index - period].volume
            }
            if (index >= period - 1) {
                result[index] = sum / period
            }
        }

        return result
    }
}
