package com.sb.ui.chart

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

data class PriceRange(
    val min: Double,
    val max: Double
) {
    val span: Double get() = max - min

    fun isValid(): Boolean = min.isFinite() && max.isFinite() && max > min
}

data class PriceTick(
    val price: Double,
    val label: String
)

data class PriceTickSet(
    val step: Double,
    val decimals: Int,
    val ticks: List<PriceTick>
)

object PriceScaleEngine {

    private const val MAX_ABS_PRICE = 1.0e12
    private const val MIN_SPAN = 1.0e-8
    private const val PADDING_RATIO = 0.08
    private const val RUBBER_COEFFICIENT = 0.55

    fun isUsablePrice(value: Double): Boolean {
        return value.isFinite() && abs(value) <= MAX_ABS_PRICE
    }

    fun autoFitRange(
        dataMin: Double,
        dataMax: Double,
        reference: Double = Double.NaN
    ): PriceRange {
        val usableMin = isUsablePrice(dataMin)
        val usableMax = isUsablePrice(dataMax)
        val usableReference = isUsablePrice(reference)

        if (!usableMin && !usableMax) {
            if (!usableReference) return PriceRange(0.0, 1.0)
            return centeredRange(reference, fallbackSpan(reference))
        }

        var low = when {
            usableMin -> dataMin
            usableMax -> dataMax
            else -> reference
        }
        var high = when {
            usableMax -> dataMax
            usableMin -> dataMin
            else -> reference
        }
        if (high < low) {
            val swap = low
            low = high
            high = swap
        }

        var span = high - low
        if (span <= MIN_SPAN) {
            val fallback = fallbackSpan((low + high) / 2.0)
            low -= fallback / 2.0
            high += fallback / 2.0
            span = high - low
        }

        val padding = span * PADDING_RATIO
        return sanitizeRange(low - padding, high + padding)
    }

    fun sanitizeRange(minPrice: Double, maxPrice: Double): PriceRange {
        if (!isUsablePrice(minPrice) || !isUsablePrice(maxPrice)) {
            return PriceRange(0.0, 1.0)
        }

        var low = minOf(minPrice, maxPrice)
        var high = maxOf(minPrice, maxPrice)

        if (high - low <= MIN_SPAN) {
            val span = fallbackSpan((low + high) / 2.0)
            low -= span / 2.0
            high += span / 2.0
        }

        low = low.coerceIn(-MAX_ABS_PRICE, MAX_ABS_PRICE)
        high = high.coerceIn(-MAX_ABS_PRICE, MAX_ABS_PRICE)
        if (high - low <= MIN_SPAN) {
            high = min(MAX_ABS_PRICE, low + MIN_SPAN * 2.0)
            low = max(-MAX_ABS_PRICE, high - MIN_SPAN * 2.0)
        }

        return PriceRange(low, high)
    }

    fun centeredRange(anchorPrice: Double, span: Double): PriceRange {
        val safeAnchor = if (isUsablePrice(anchorPrice)) anchorPrice else 0.0
        val safeSpan = if (span.isFinite()) max(abs(span), MIN_SPAN) else 1.0
        val low = safeAnchor - safeSpan / 2.0
        return sanitizeRange(low, low + safeSpan)
    }

    fun rangeWithAnchor(
        anchorPrice: Double,
        span: Double,
        anchorRatio: Double = 0.5
    ): PriceRange {
        val safeAnchor = if (isUsablePrice(anchorPrice)) anchorPrice else 0.0
        val safeSpan = if (span.isFinite()) max(abs(span), MIN_SPAN) else 1.0
        val ratio = anchorRatio.coerceIn(0.0, 1.0)
        val low = safeAnchor - safeSpan * ratio
        return sanitizeRange(low, low + safeSpan)
    }

    fun minZoomSpan(autoRange: PriceRange): Double {
        val base = if (autoRange.isValid()) autoRange.span else fallbackSpan(0.0)
        return max(MIN_SPAN, base * 0.01)
    }

    fun maxZoomSpan(autoRange: PriceRange): Double {
        val base = if (autoRange.isValid()) autoRange.span else fallbackSpan(0.0)
        return max(minZoomSpan(autoRange), base * 20.0)
    }

    fun elasticSpan(
        rawSpan: Double,
        minSpan: Double,
        maxSpan: Double
    ): Double {
        if (!rawSpan.isFinite()) return minSpan
        val safeMin = if (minSpan.isFinite()) max(abs(minSpan), MIN_SPAN) else MIN_SPAN
        val safeMax = if (maxSpan.isFinite()) max(safeMin, abs(maxSpan)) else safeMin

        return when {
            rawSpan < safeMin -> {
                val distance = rubberBand(safeMin - rawSpan, safeMin)
                max(MIN_SPAN, safeMin - distance)
            }

            rawSpan > safeMax -> {
                val distance = rubberBand(rawSpan - safeMax, max(safeMax * 0.35, safeMin))
                safeMax + distance
            }

            else -> rawSpan
        }
    }

    fun rubberBand(
        distance: Double,
        dimension: Double,
        coefficient: Double = RUBBER_COEFFICIENT
    ): Double {
        if (!distance.isFinite() || distance <= 0.0) return 0.0
        val safeDimension = if (dimension.isFinite() && dimension > 0.0) dimension else 1.0
        return (1.0 - 1.0 / (distance * coefficient / safeDimension + 1.0)) * safeDimension
    }

    fun rubberBandSigned(
        value: Double,
        dimension: Double,
        coefficient: Double = RUBBER_COEFFICIENT
    ): Double {
        if (!value.isFinite()) return 0.0
        return sign(value) * rubberBand(abs(value), dimension, coefficient)
    }

    fun priceToY(
        price: Double,
        range: PriceRange,
        top: Float,
        bottom: Float
    ): Float {
        if (!range.isValid() || !price.isFinite()) return bottom
        val ratio = (price - range.min) / range.span
        return (bottom - ratio * (bottom - top)).toFloat()
    }

    fun priceForY(
        y: Float,
        range: PriceRange,
        top: Float,
        bottom: Float
    ): Double {
        val height = bottom - top
        if (!range.isValid() || height <= 0f) return range.min
        val ratio = ((bottom - y) / height).coerceIn(0f, 1f)
        return range.min + ratio.toDouble() * range.span
    }

    fun ticks(range: PriceRange, maxTicks: Int): PriceTickSet {
        val safeRange = sanitizeRange(range.min, range.max)
        val targetTicks = maxTicks.coerceIn(2, 12)
        val targetIntervals = max(1, targetTicks - 1)

        var step = niceStep(safeRange.span / targetIntervals)
        var prices = buildTickPrices(safeRange, step)
        var guard = 0

        while (prices.size > targetTicks && guard < 16) {
            step = niceStep(step * 1.5)
            prices = buildTickPrices(safeRange, step)
            guard++
        }

        if (prices.isEmpty()) {
            val mid = (safeRange.min + safeRange.max) / 2.0
            prices = listOf(mid)
        }

        val decimals = decimalsFor(step, safeRange)
        return PriceTickSet(
            step = step,
            decimals = decimals,
            ticks = prices.map { price ->
                PriceTick(price, formatPrice(price, decimals))
            }
        )
    }

    fun formatPrice(value: Double, decimals: Int): String {
        if (!value.isFinite()) return "—"
        val safeDecimals = decimals.coerceIn(0, 4)
        return String.format(Locale.US, "%.${safeDecimals}f", value)
    }

    private fun buildTickPrices(range: PriceRange, step: Double): List<Double> {
        if (!step.isFinite() || step <= 0.0) return emptyList()

        val firstIndex = ceil(range.min / step - 1.0e-9).toLong()
        val lastIndex = floor(range.max / step + 1.0e-9).toLong()
        if (lastIndex < firstIndex) return emptyList()

        val count = min(200L, lastIndex - firstIndex + 1L).toInt()
        val prices = ArrayList<Double>(count)
        for (offset in 0 until count) {
            val price = (firstIndex + offset) * step
            if (price < range.min - step * 1.0e-6) continue
            if (price > range.max + step * 1.0e-6) break
            prices.add(normalizeZero(price, step))
        }
        return prices
    }

    private fun niceStep(rawStep: Double): Double {
        if (!rawStep.isFinite() || rawStep <= 0.0) return 1.0
        val exponent = floor(log10(rawStep))
        val magnitude = 10.0.pow(exponent)
        val fraction = rawStep / magnitude
        val niceFraction = when {
            fraction <= 1.0 -> 1.0
            fraction <= 2.0 -> 2.0
            fraction <= 2.5 -> 2.5
            fraction <= 5.0 -> 5.0
            else -> 10.0
        }
        return niceFraction * magnitude
    }

    private fun decimalsFor(step: Double, range: PriceRange): Int {
        val stepDecimals = when {
            step >= 0.01 -> 2
            step >= 0.001 -> 3
            else -> 4
        }
        val magnitude = max(abs(range.min), abs(range.max))
        val priceDecimals = if (magnitude < 1.0) 4 else 2
        return max(stepDecimals, priceDecimals).coerceAtMost(4)
    }

    private fun normalizeZero(value: Double, step: Double): Double {
        return if (abs(value) < step * 1.0e-9) 0.0 else value
    }

    private fun fallbackSpan(anchor: Double): Double {
        val magnitude = abs(anchor)
        return max(0.01, magnitude * 0.01)
    }
}
