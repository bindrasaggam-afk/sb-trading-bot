package com.sb.ui.chart

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
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

    data class CandleRange(
        val startFloat: Float,
        val visibleFloat: Float,
        val start: Int,
        val end: Int,
        val slot: Float
    )

    data class PanResult(
        val scrollOffset: Float,
        val overscroll: Float,
        val atEnd: Boolean
    )

    data class PanelArea(
        val index: Int,
        val top: Float,
        val bottom: Float
    )

    data class VerticalLayout(
        val priceTop: Float,
        val priceBottom: Float,
        val volumeTop: Float,
        val volumeBottom: Float,
        val panels: List<PanelArea>
    )

    private const val MAX_ABS_PRICE = 1.0e12
    private const val MIN_SPAN = 1.0e-8
    private const val MIN_ZOOM_SPAN = 0.01
    private const val MAX_ZOOM_SPAN = 1_000_000.0
    private const val PADDING_RATIO = 0.08
    private const val RUBBER_COEFFICIENT = 0.55
    private const val MILLIS_PER_DAY = 86_400_000L

    const val AXIS_ZOOM_SENSITIVITY = 2.0f
    const val AXIS_VELOCITY_PROJECTION = 0.10f
    const val PAN_VELOCITY_PROJECTION = 0.16f

    fun candleRange(
        scrollOffset: Float,
        visibleCount: Float,
        candleCount: Int,
        left: Float,
        right: Float
    ): CandleRange? {
        if (candleCount <= 0 || right <= left) return null
        val visible = visibleCount.coerceIn(1f, candleCount.toFloat())
        val startFloat = scrollOffset.coerceIn(0f, maxScroll(candleCount, visible))
        val first = max(0, floor(startFloat).toInt())
        val end = min(candleCount, ceil(startFloat + visible).toInt())
        if (first >= end) return null
        return CandleRange(
            startFloat = startFloat,
            visibleFloat = visible,
            start = first,
            end = end,
            slot = (right - left) / visible
        )
    }

    fun xFor(index: Int, range: CandleRange, left: Float, overscroll: Float): Float {
        return left + (index - range.startFloat + 0.5f) * range.slot + overscroll
    }

    fun indexAt(x: Float, range: CandleRange, left: Float, overscroll: Float): Int {
        val raw = floor(range.startFloat + (x - left - overscroll) / range.slot).toInt()
        return raw.coerceIn(range.start, range.end - 1)
    }

    fun maxScroll(candleCount: Int, visibleCount: Float): Float {
        return max(0f, candleCount.toFloat() - visibleCount)
    }

    fun dragOffset(rawOffset: Float, deltaX: Float, slot: Float): Float {
        if (!slot.isFinite() || slot <= 0f) return rawOffset
        return rawOffset - deltaX / slot
    }

    fun panOffset(
        rawOffset: Float,
        slot: Float,
        maxScroll: Float,
        plotWidth: Float,
        maxOverscrollPx: Float
    ): PanResult {
        val clamped = rawOffset.coerceIn(0f, max(0f, maxScroll))
        val overscrollPx = (rawOffset - clamped) * slot
        val dimension = max(abs(maxOverscrollPx), plotWidth * 0.35f).toDouble()
        val overscroll = -rubberBandSigned(overscrollPx.toDouble(), dimension).toFloat()
        return PanResult(
            scrollOffset = clamped,
            overscroll = overscroll,
            atEnd = clamped >= maxScroll - 0.01f && abs(overscroll) < 0.5f
        )
    }

    fun flingTargetOffset(
        currentOffset: Float,
        velocityX: Float,
        slot: Float,
        maxScroll: Float,
        projection: Float = PAN_VELOCITY_PROJECTION
    ): Float {
        if (!slot.isFinite() || slot <= 0f) {
            return currentOffset.coerceIn(0f, max(0f, maxScroll))
        }
        val projected = currentOffset - velocityX * projection / slot
        return projected.coerceIn(0f, max(0f, maxScroll))
    }

    fun anchoredStart(
        anchorIndex: Float,
        focusX: Float,
        left: Float,
        newSlot: Float,
        candleCount: Int,
        newVisible: Float
    ): Float {
        val candleAnchor = floor(anchorIndex) + 0.5f
        val newStart = candleAnchor - (focusX - left) / newSlot
        return newStart.coerceIn(0f, maxScroll(candleCount, newVisible))
    }

    fun axisZoomTravel(
        startY: Float,
        lastY: Float,
        velocityY: Float,
        projection: Float = AXIS_VELOCITY_PROJECTION
    ): Float {
        return (startY - lastY) - velocityY * projection
    }

    fun axisZoomFactor(
        travel: Float,
        height: Float,
        sensitivity: Float = AXIS_ZOOM_SENSITIVITY
    ): Double {
        return exp((travel / max(1f, height) * sensitivity).toDouble())
    }

    fun axisZoomRange(
        startRange: PriceRange,
        travel: Float,
        height: Float,
        anchorPrice: Double,
        anchorRatio: Double,
        minSpan: Double,
        maxSpan: Double,
        sensitivity: Float = AXIS_ZOOM_SENSITIVITY,
        elastic: Boolean = true
    ): PriceRange {
        val rawSpan = startRange.span * axisZoomFactor(travel, height, sensitivity)
        val span = if (elastic) {
            elasticSpan(rawSpan, minSpan, maxSpan)
        } else {
            rawSpan.coerceIn(minSpan, maxSpan)
        }
        return rangeWithAnchor(anchorPrice, span, anchorRatio)
    }

    fun isInsidePlot(
        x: Float,
        y: Float,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float
    ): Boolean {
        return right > left && bottom > top &&
            x >= left && x <= right && y >= top && y <= bottom
    }

    fun verticalLayout(
        top: Float,
        bottom: Float,
        subCount: Int,
        minPanel: Float,
        gap: Float,
        minMain: Float,
        minVolume: Float
    ): VerticalLayout? {
        val usable = bottom - top
        if (usable <= 0f) return null

        if (subCount <= 0) {
            val priceBottom = top + usable * 0.78f
            val volumeBottom = min(bottom, priceBottom + usable * 0.18f)
            return VerticalLayout(top, priceBottom, priceBottom, volumeBottom, emptyList())
        }

        val gaps = gap * subCount
        val reserved = gaps + minMain + minVolume + subCount.toFloat()
        val scale = if (usable < reserved) max(0f, usable / reserved) else 1f
        val safeGap = gap * scale
        val safePanel = max(1f, minPanel * scale)
        val safeMain = max(1f, minMain * scale)
        val safeVolume = max(1f, minVolume * scale)

        val targetSub = max(
            subCount.toFloat() * safePanel,
            usable * (if (subCount == 1) 0.30f else 0.42f)
        )
        val subArea = min(
            targetSub,
            max(0f, usable - safeGap * subCount - safeMain - safeVolume)
        )
        val panelHeight = max(
            1f,
            (subArea - safeGap * (subCount - 1)) / subCount
        )
        val volumeArea = min(
            max(safeVolume, usable * 0.10f),
            max(0f, usable - safeGap * subCount - subArea - safeMain)
        )
        val priceArea = max(
            1f,
            usable - safeGap * subCount - subArea - volumeArea
        )

        val priceBottom = top + priceArea
        val volumeBottom = priceBottom + volumeArea
        val subTop = volumeBottom + safeGap
        val panels = (0 until subCount).map { index ->
            val panelTop = subTop + index * (panelHeight + safeGap)
            val panelBottom = max(panelTop, min(bottom, panelTop + panelHeight))
            PanelArea(index, panelTop, panelBottom)
        }

        return VerticalLayout(top, priceBottom, priceBottom, volumeBottom, panels)
    }

    fun needsDateLabel(firstTimestamp: Long, lastTimestamp: Long): Boolean {
        if (firstTimestamp <= 0L || lastTimestamp <= 0L) return false
        return lastTimestamp - firstTimestamp >= MILLIS_PER_DAY * 2L
    }

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
        return max(MIN_SPAN, min(base * 0.01, MIN_ZOOM_SPAN))
    }

    fun maxZoomSpan(autoRange: PriceRange): Double {
        val base = if (autoRange.isValid()) autoRange.span else fallbackSpan(0.0)
        return max(minZoomSpan(autoRange), max(base * 20.0, MAX_ZOOM_SPAN))
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
