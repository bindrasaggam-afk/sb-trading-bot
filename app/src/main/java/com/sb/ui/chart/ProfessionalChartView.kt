package com.sb.ui.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.sb.ui.theme.AppDesign
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ProfessionalChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var chartStyle: ChartStyle = ChartStyle.CANDLE
        set(value) {
            field = value
            rebuildDisplayCandles()
            invalidate()
        }

    var activeTool: ChartTool = ChartTool.CROSSHAIR
        set(value) {
            field = value
            pendingPoints.clear()
            crosshairVisible = false
            onCrosshairChanged?.invoke(null, -1)
            invalidate()
        }

    var magnetEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var indicators: Set<ChartIndicator> = emptySet()
        set(value) {
            field = value
            indicatorSignature = 0
            invalidate()
        }

    var onCrosshairChanged: ((ChartCandle?, Int) -> Unit)? = null
    var onTextDrawingRequested: ((Int, Double) -> Unit)? = null

    private var source: List<ChartCandle> = emptyList()
    private var candles: List<ChartCandle> = emptyList()
    private val drawings = mutableListOf<ChartDrawing>()
    private val pendingPoints = mutableListOf<ChartPoint>()

    private var scrollOffset = 0f
    private var visibleCount = 40f
    private var followLatest = true

    private var crosshairVisible = false
    private var crosshairX = 0f
    private var crosshairY = 0f
    private var crosshairIndex = -1

    private var lastTouchX = 0f
    private var previewX = 0f
    private var previewY = 0f
    private var lastGeometry: Geometry? = null

    private var indicatorSignature = 0
    private var ema20: DoubleArray = DoubleArray(0)
    private var sma50: DoubleArray = DoubleArray(0)
    private var vwap: DoubleArray = DoubleArray(0)
    private var rsi14: DoubleArray = DoubleArray(0)
    private var macdResult: IndicatorMath.MacdResult? = null
    private var bollingerResult: IndicatorMath.BollingerResult? = null
    private var supertrendResult: IndicatorMath.SupertrendResult? = null

    private val density = context.resources.displayMetrics.density
    private val timeFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US)
    private val axisTimeFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)
    private val axisIntradayFormat = SimpleDateFormat("HH:mm", Locale.US)

    private val bullishColor = AppDesign.positive
    private val bearishColor = AppDesign.negative

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppDesign.divider
        strokeWidth = dp(0.7f)
        style = Paint.Style.STROKE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppDesign.textMuted
        textSize = dp(9f)
        textAlign = Paint.Align.RIGHT
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppDesign.textMuted
        textSize = dp(9f)
    }
    private val candlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeWidth = dp(1.2f)
    }
    private val wickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.1f)
        strokeCap = Paint.Cap.ROUND
    }
    private val volumePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 225, 232, 240)
        strokeWidth = dp(0.8f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    }
    private val crosshairTagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(58, 64, 74)
        style = Paint.Style.FILL
    }
    private val tooltipBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 18, 20, 25)
        style = Paint.Style.FILL
    }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppDesign.textPrimary
        textSize = dp(9.5f)
    }
    private val emaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7)
        strokeWidth = dp(1.3f)
        style = Paint.Style.STROKE
    }
    private val smaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(56, 189, 248)
        strokeWidth = dp(1.3f)
        style = Paint.Style.STROKE
    }
    private val vwapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(217, 70, 239)
        strokeWidth = dp(1.3f)
        style = Paint.Style.STROKE
    }
    private val bollingerBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 148, 163, 184)
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val bollingerMiddlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(148, 163, 184)
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val supertrendUpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(34, 197, 94)
        strokeWidth = dp(1.4f)
        style = Paint.Style.STROKE
    }
    private val supertrendDownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(239, 68, 68)
        strokeWidth = dp(1.4f)
        style = Paint.Style.STROKE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(56, 189, 248)
        strokeWidth = dp(1.6f)
        style = Paint.Style.STROKE
    }
    private val lineFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rsiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(250, 204, 21)
        strokeWidth = dp(1.2f)
        style = Paint.Style.STROKE
    }
    private val macdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(56, 189, 248)
        strokeWidth = dp(1.2f)
        style = Paint.Style.STROKE
    }
    private val signalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(251, 146, 60)
        strokeWidth = dp(1.2f)
        style = Paint.Style.STROKE
    }
    private val histogramPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val drawingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7)
        strokeWidth = dp(1.2f)
        style = Paint.Style.STROKE
    }
    private val drawingFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(28, 255, 193, 7)
        style = Paint.Style.FILL
    }
    private val pendingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val maxLimit = max(MIN_VISIBLE, candles.size.toFloat().coerceAtLeast(MIN_VISIBLE))
            visibleCount = (visibleCount / detector.scaleFactor).coerceIn(MIN_VISIBLE, maxLimit)
            if (followLatest) {
                scrollOffset = max(0f, candles.size - visibleCount)
            }
            invalidate()
            return true
        }
    })

    init {
        setBackgroundColor(AppDesign.background)
        isClickable = true
        isFocusable = true
    }

    fun setChartData(
        data: List<ChartCandle>,
        style: ChartStyle,
        preserveViewport: Boolean = false
    ) {
        source = data
        chartStyle = style
        crosshairVisible = false
        crosshairIndex = -1

        if (!preserveViewport) {
            visibleCount = 40f
            followLatest = true
            drawings.clear()
            pendingPoints.clear()
            scrollOffset = 0f
        }

        if (followLatest) {
            scrollOffset = max(0f, candles.size - visibleCount)
        }

        rebuildDisplayCandles()
        invalidate()
    }

    /** Move the viewport to the newest available candles without clearing drawings. */
    fun scrollToLatest() {
        followLatest = true
        scrollOffset = max(0f, candles.size - visibleCount)
        crosshairVisible = false
        crosshairIndex = -1
        invalidate()
    }

    fun addTextDrawing(index: Int, price: Double, text: String) {
        drawings.add(
            ChartDrawing(
                tool = ChartTool.TEXT,
                points = mutableListOf(ChartPoint(index, price)),
                text = text
            )
        )
        invalidate()
    }

    fun clearDrawings() {
        drawings.clear()
        pendingPoints.clear()
        invalidate()
    }

    fun drawingCount(): Int = drawings.size

    private fun rebuildDisplayCandles() {
        candles = when (chartStyle) {
            ChartStyle.HEIKIN_ASHI -> buildHeikinAshi(source)
            ChartStyle.CANDLE, ChartStyle.LINE, ChartStyle.BAR -> ArrayList(source)
        }
        indicatorSignature = 0
        if (followLatest) {
            scrollOffset = max(0f, candles.size - visibleCount)
        }
    }

    private fun buildHeikinAshi(input: List<ChartCandle>): List<ChartCandle> {
        val result = ArrayList<ChartCandle>(input.size)
        if (input.isEmpty()) return result

        var previousOpen = (input[0].open + input[0].close) / 2.0
        var previousClose = (input[0].open + input[0].high + input[0].low + input[0].close) / 4.0

        for (index in input.indices) {
            val candle = input[index]
            val haClose = (candle.open + candle.high + candle.low + candle.close) / 4.0
            val haOpen = if (index == 0) {
                (candle.open + candle.close) / 2.0
            } else {
                (previousOpen + previousClose) / 2.0
            }
            val haHigh = max(candle.high, max(haOpen, haClose))
            val haLow = min(candle.low, min(haOpen, haClose))

            result.add(
                ChartCandle(
                    timestamp = candle.timestamp,
                    open = haOpen,
                    high = haHigh,
                    low = haLow,
                    close = haClose,
                    volume = candle.volume
                )
            )
            previousOpen = haOpen
            previousClose = haClose
        }
        return result
    }

    private fun ensureIndicators() {
        val signature = System.identityHashCode(candles) * 31 + indicators.hashCode()
        if (signature == indicatorSignature) return
        indicatorSignature = signature

        val closes = candles.map { it.close }
        ema20 = if (ChartIndicator.EMA_20 in indicators) {
            IndicatorMath.ema(closes, 20)
        } else {
            DoubleArray(0)
        }
        sma50 = if (ChartIndicator.SMA_50 in indicators) {
            IndicatorMath.sma(closes, 50)
        } else {
            DoubleArray(0)
        }
        vwap = if (ChartIndicator.VWAP in indicators) {
            IndicatorMath.vwap(candles)
        } else {
            DoubleArray(0)
        }
        rsi14 = if (ChartIndicator.RSI_14 in indicators) {
            IndicatorMath.rsi(closes, 14)
        } else {
            DoubleArray(0)
        }
        macdResult = if (ChartIndicator.MACD in indicators) {
            IndicatorMath.macd(closes)
        } else {
            null
        }
        bollingerResult = if (ChartIndicator.BOLLINGER in indicators) {
            IndicatorMath.bollinger(closes)
        } else {
            null
        }
        supertrendResult = if (ChartIndicator.SUPERTREND in indicators) {
            IndicatorMath.supertrend(candles)
        } else {
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (candles.isEmpty()) {
            labelPaint.color = AppDesign.textMuted
            labelPaint.textSize = dp(12f)
            canvas.drawText("No candle data", dp(16f), dp(28f), labelPaint)
            return
        }

        ensureIndicators()
        val geometry = computeGeometry() ?: return
        lastGeometry = geometry

        drawPriceGrid(canvas, geometry)
        drawVolume(canvas, geometry)
        drawPriceSeries(canvas, geometry)
        drawOverlays(canvas, geometry)
        drawSubCharts(canvas, geometry)
        drawDrawings(canvas, geometry)
        drawTimeAxis(canvas, geometry)
        drawLegend(canvas, geometry)
        drawCrosshair(canvas, geometry)
    }

    private fun computeGeometry(): Geometry? {
        if (candles.isEmpty()) return null

        val left = dp(4f)
        val right = width - dp(62f)
        val top = dp(6f)
        val bottom = height - dp(18f)
        if (right <= left || bottom <= top) return null

        val visible = max(1, min(candles.size, visibleCount.toInt()))
        val maxStart = max(0, candles.size - visible)
        val start = min(maxStart, max(0, scrollOffset.toInt()))
        val end = min(candles.size, start + visible)
        if (start >= end) return null

        val usable = bottom - top
        val subIndicators = ArrayList<ChartIndicator>()
        if (ChartIndicator.RSI_14 in indicators) subIndicators.add(ChartIndicator.RSI_14)
        if (ChartIndicator.MACD in indicators) subIndicators.add(ChartIndicator.MACD)
        val priceRatio = when (subIndicators.size) {
            0 -> 0.78f
            1 -> 0.60f
            else -> 0.48f
        }
        val volumeRatio = if (subIndicators.isEmpty()) 0.18f else 0.10f

        val priceTop = top
        val priceBottom = priceTop + usable * priceRatio
        val volumeTop = priceBottom
        val volumeBottom = min(bottom, volumeTop + usable * volumeRatio)

        val subCharts = ArrayList<SubChartArea>()
        if (subIndicators.isNotEmpty()) {
            val subTop = volumeBottom + dp(2f)
            val subHeight = max(dp(28f), (bottom - subTop) / subIndicators.size)
            subIndicators.forEachIndexed { index, indicator ->
                val areaTop = subTop + subHeight * index
                subCharts.add(
                    SubChartArea(
                        indicator = indicator,
                        top = areaTop,
                        bottom = min(bottom, areaTop + subHeight - dp(2f))
                    )
                )
            }
        }

        var maxPrice = candles[start].high
        var minPrice = candles[start].low
        for (index in start until end) {
            val candle = candles[index]
            if (candle.high > maxPrice) maxPrice = candle.high
            if (candle.low < minPrice) minPrice = candle.low
        }

        fun includeOverlay(values: DoubleArray?) {
            if (values == null || values.isEmpty()) return
            for (index in start until end) {
                val value = values.getOrNull(index) ?: continue
                if (!value.isFinite()) continue
                if (value > maxPrice) maxPrice = value
                if (value < minPrice) minPrice = value
            }
        }

        includeOverlay(ema20)
        includeOverlay(sma50)
        includeOverlay(vwap)
        bollingerResult?.let {
            includeOverlay(it.upper)
            includeOverlay(it.lower)
        }
        supertrendResult?.let { includeOverlay(it.line) }

        var priceRange = maxPrice - minPrice
        if (priceRange <= 0.0) priceRange = max(1.0, abs(maxPrice) * 0.01)
        val padding = priceRange * 0.08
        maxPrice += padding
        minPrice -= padding
        if (maxPrice - minPrice <= 0.0) {
            maxPrice = minPrice + 1.0
        }

        return Geometry(
            left = left,
            right = right,
            top = top,
            bottom = bottom,
            priceTop = priceTop,
            priceBottom = priceBottom,
            volumeTop = volumeTop,
            volumeBottom = volumeBottom,
            start = start,
            end = end,
            visible = visible,
            slot = (right - left) / visible.toFloat(),
            minPrice = minPrice,
            maxPrice = maxPrice,
            subCharts = subCharts
        )
    }

    private fun priceY(price: Double, geometry: Geometry): Float {
        val range = geometry.maxPrice - geometry.minPrice
        return geometry.priceBottom -
            (((price - geometry.minPrice) / range) * (geometry.priceBottom - geometry.priceTop)).toFloat()
    }

    private fun xFor(index: Int, geometry: Geometry): Float {
        return geometry.left + (index - geometry.start + 0.5f) * geometry.slot
    }

    private fun indexAt(x: Float, geometry: Geometry): Int {
        val raw = geometry.start + ((x - geometry.left) / geometry.slot).toInt()
        return raw.coerceIn(geometry.start, geometry.end - 1)
    }

    private fun priceAt(y: Float, geometry: Geometry): Double {
        val ratio = ((geometry.priceBottom - y) / (geometry.priceBottom - geometry.priceTop))
            .coerceIn(0f, 1f)
        return geometry.minPrice + ratio * (geometry.maxPrice - geometry.minPrice)
    }

    private fun drawPriceGrid(canvas: Canvas, geometry: Geometry) {
        axisPaint.color = AppDesign.textMuted
        for (index in 0..4) {
            val y = geometry.priceTop + (geometry.priceBottom - geometry.priceTop) * index / 4f
            canvas.drawLine(geometry.left, y, geometry.right, y, gridPaint)
            val price = geometry.maxPrice -
                ((geometry.maxPrice - geometry.minPrice) * index / 4.0)
            canvas.drawText(
                formatPrice(price),
                width - dp(4f),
                y + dp(3.5f),
                axisPaint
            )
        }
    }

    private fun drawVolume(canvas: Canvas, geometry: Geometry) {
        var maxVolume = 1L
        for (index in geometry.start until geometry.end) {
            if (candles[index].volume > maxVolume) maxVolume = candles[index].volume
        }

        val candleWidth = max(dp(1.5f), min(dp(12f), geometry.slot * 0.62f))
        for (index in geometry.start until geometry.end) {
            val candle = candles[index]
            val x = xFor(index, geometry)
            val height = (candle.volume.toFloat() / maxVolume) * (geometry.volumeBottom - geometry.volumeTop)
            val color = if (candle.close >= candle.open) bullishColor else bearishColor
            volumePaint.color = Color.argb(95, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawRect(
                x - candleWidth / 2f,
                geometry.volumeBottom - height,
                x + candleWidth / 2f,
                geometry.volumeBottom,
                volumePaint
            )
        }

        canvas.drawLine(
            geometry.left,
            geometry.volumeTop,
            geometry.right,
            geometry.volumeTop,
            gridPaint
        )
    }

    private fun drawPriceSeries(canvas: Canvas, geometry: Geometry) {
        when (chartStyle) {
            ChartStyle.LINE -> drawLineSeries(canvas, geometry)
            ChartStyle.CANDLE, ChartStyle.HEIKIN_ASHI -> drawCandles(canvas, geometry)
            ChartStyle.BAR -> drawBars(canvas, geometry)
        }
    }

    private fun drawBars(canvas: Canvas, geometry: Geometry) {
        val tickWidth = max(dp(2f), min(dp(6f), geometry.slot * 0.35f))

        for (index in geometry.start until geometry.end) {
            val candle = candles[index]
            val x = xFor(index, geometry)
            val color = if (candle.close >= candle.open) bullishColor else bearishColor
            wickPaint.color = color

            canvas.drawLine(
                x,
                priceY(candle.high, geometry),
                x,
                priceY(candle.low, geometry),
                wickPaint
            )
            val openY = priceY(candle.open, geometry)
            val closeY = priceY(candle.close, geometry)
            canvas.drawLine(x - tickWidth / 2f, openY, x, openY, wickPaint)
            canvas.drawLine(x, closeY, x + tickWidth / 2f, closeY, wickPaint)
        }
    }

    private fun drawCandles(canvas: Canvas, geometry: Geometry) {
        val candleWidth = max(dp(1.5f), min(dp(12f), geometry.slot * 0.62f))
        val minimumBody = dp(1.2f)

        for (index in geometry.start until geometry.end) {
            val candle = candles[index]
            val x = xFor(index, geometry)
            val isBullish = candle.close >= candle.open
            val color = if (isBullish) bullishColor else bearishColor

            wickPaint.color = color
            canvas.drawLine(
                x,
                priceY(candle.high, geometry),
                x,
                priceY(candle.low, geometry),
                wickPaint
            )

            val openY = priceY(candle.open, geometry)
            val closeY = priceY(candle.close, geometry)
            val bodyTop = min(openY, closeY)
            val bodyBottom = max(openY, closeY)

            candlePaint.color = color
            canvas.drawRect(
                x - candleWidth / 2f,
                bodyTop,
                x + candleWidth / 2f,
                max(bodyTop + minimumBody, bodyBottom),
                candlePaint
            )
        }
    }

    private fun drawLineSeries(canvas: Canvas, geometry: Geometry) {
        val path = Path()
        val fillPath = Path()
        var started = false

        for (index in geometry.start until geometry.end) {
            val x = xFor(index, geometry)
            val y = priceY(candles[index].close, geometry)
            if (!started) {
                path.moveTo(x, y)
                fillPath.moveTo(x, geometry.priceBottom)
                fillPath.lineTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        if (!started) return

        fillPath.lineTo(xFor(geometry.end - 1, geometry), geometry.priceBottom)
        fillPath.close()
        lineFillPaint.shader = LinearGradient(
            0f,
            geometry.priceTop,
            0f,
            geometry.priceBottom,
            Color.argb(80, 56, 189, 248),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, lineFillPaint)
        canvas.drawPath(path, linePaint)
    }

    private fun drawOverlays(canvas: Canvas, geometry: Geometry) {
        if (ema20.isNotEmpty()) drawSeries(canvas, ema20, geometry, emaPaint) { it }
        if (sma50.isNotEmpty()) drawSeries(canvas, sma50, geometry, smaPaint) { it }
        if (vwap.isNotEmpty()) drawSeries(canvas, vwap, geometry, vwapPaint) { it }
        bollingerResult?.let { result ->
            drawSeries(canvas, result.upper, geometry, bollingerBandPaint) { it }
            drawSeries(canvas, result.lower, geometry, bollingerBandPaint) { it }
            drawSeries(canvas, result.middle, geometry, bollingerMiddlePaint) { it }
        }
        supertrendResult?.let { drawSupertrend(canvas, it, geometry) }
    }

    private fun drawSupertrend(
        canvas: Canvas,
        result: IndicatorMath.SupertrendResult,
        geometry: Geometry
    ) {
        var previousX = Float.NaN
        var previousY = Float.NaN

        for (index in geometry.start until geometry.end) {
            val value = result.line[index]
            if (!value.isFinite()) {
                previousX = Float.NaN
                previousY = Float.NaN
                continue
            }

            val x = xFor(index, geometry)
            val y = priceY(value, geometry)
            val direction = result.direction[index]

            if (previousX.isFinite() && previousY.isFinite()) {
                val paint = if (direction >= 0) {
                    supertrendUpPaint
                } else {
                    supertrendDownPaint
                }
                canvas.drawLine(previousX, previousY, x, y, paint)
            }

            previousX = x
            previousY = y
        }
    }

    private fun drawSeries(
        canvas: Canvas,
        values: DoubleArray,
        geometry: Geometry,
        paint: Paint,
        mapper: (Double) -> Double
    ) {
        val path = Path()
        var started = false
        for (index in geometry.start until geometry.end) {
            val value = values.getOrNull(index) ?: continue
            if (!value.isFinite()) {
                started = false
                continue
            }
            val x = xFor(index, geometry)
            val y = priceY(mapper(value), geometry)
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawSubCharts(canvas: Canvas, geometry: Geometry) {
        geometry.subCharts.forEach { area ->
            when (area.indicator) {
                ChartIndicator.RSI_14 -> drawRsi(canvas, geometry, area)
                ChartIndicator.MACD -> drawMacd(canvas, geometry, area)
                else -> Unit
            }
        }
    }

    private fun drawRsi(canvas: Canvas, geometry: Geometry, area: SubChartArea) {
        if (rsi14.isEmpty()) return

        val height = area.bottom - area.top
        fun rsiY(value: Double): Float {
            return area.bottom - ((value / 100.0) * height).toFloat()
        }

        canvas.drawLine(geometry.left, area.top, geometry.right, area.top, gridPaint)
        listOf(30.0, 50.0, 70.0).forEach { level ->
            val y = rsiY(level)
            gridPaint.color = if (level == 50.0) AppDesign.divider else Color.argb(48, 244, 67, 54)
            canvas.drawLine(geometry.left, y, geometry.right, y, gridPaint)
            labelPaint.color = AppDesign.textMuted
            canvas.drawText(level.toInt().toString(), geometry.left + dp(2f), y - dp(2f), labelPaint)
        }
        gridPaint.color = AppDesign.divider

        val path = Path()
        var started = false
        for (index in geometry.start until geometry.end) {
            val value = rsi14.getOrNull(index) ?: continue
            if (!value.isFinite()) {
                started = false
                continue
            }
            val x = xFor(index, geometry)
            val y = rsiY(value)
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, rsiPaint)

        val lastValue = rsi14.getOrNull(geometry.end - 1)
        labelPaint.color = rsiPaint.color
        val text = if (lastValue != null && lastValue.isFinite()) {
            "RSI 14  ${String.format(Locale.US, "%.1f", lastValue)}"
        } else {
            "RSI 14"
        }
        canvas.drawText(text, geometry.left + dp(2f), area.top + dp(10f), labelPaint)
    }

    private fun drawMacd(canvas: Canvas, geometry: Geometry, area: SubChartArea) {
        val result = macdResult ?: return
        val height = area.bottom - area.top
        var minValue = 0.0
        var maxValue = 0.0
        for (index in geometry.start until geometry.end) {
            result.macd.getOrNull(index)?.takeIf { it.isFinite() }?.let {
                if (it < minValue) minValue = it
                if (it > maxValue) maxValue = it
            }
            result.signal.getOrNull(index)?.takeIf { it.isFinite() }?.let {
                if (it < minValue) minValue = it
                if (it > maxValue) maxValue = it
            }
        }
        var range = maxValue - minValue
        if (range <= 0.0) {
            minValue -= 1.0
            maxValue += 1.0
            range = 2.0
        }
        val padding = range * 0.10
        minValue -= padding
        maxValue += padding
        range = maxValue - minValue

        fun macdY(value: Double): Float {
            return area.bottom - (((value - minValue) / range) * height).toFloat()
        }

        canvas.drawLine(geometry.left, area.top, geometry.right, area.top, gridPaint)
        val zeroY = macdY(0.0)
        canvas.drawLine(geometry.left, zeroY, geometry.right, zeroY, gridPaint)

        val candleWidth = max(dp(1f), min(dp(10f), geometry.slot * 0.45f))
        for (index in geometry.start until geometry.end) {
            val value = result.histogram.getOrNull(index) ?: continue
            if (!value.isFinite()) continue
            val x = xFor(index, geometry)
            val y = macdY(value)
            histogramPaint.color = if (value >= 0.0) {
                Color.argb(130, 34, 197, 94)
            } else {
                Color.argb(130, 239, 68, 68)
            }
            canvas.drawRect(
                x - candleWidth / 2f,
                min(zeroY, y),
                x + candleWidth / 2f,
                max(zeroY, y),
                histogramPaint
            )
        }

        drawMacdSeries(canvas, result.macd, geometry, macdPaint, ::macdY)
        drawMacdSeries(canvas, result.signal, geometry, signalPaint, ::macdY)

        val lastMacd = result.macd.getOrNull(geometry.end - 1)
        labelPaint.color = macdPaint.color
        val text = if (lastMacd != null && lastMacd.isFinite()) {
            "MACD  ${String.format(Locale.US, "%.2f", lastMacd)}"
        } else {
            "MACD"
        }
        canvas.drawText(text, geometry.left + dp(2f), area.top + dp(10f), labelPaint)
    }

    private fun drawMacdSeries(
        canvas: Canvas,
        values: DoubleArray,
        geometry: Geometry,
        paint: Paint,
        mapper: (Double) -> Float
    ) {
        val path = Path()
        var started = false
        for (index in geometry.start until geometry.end) {
            val value = values.getOrNull(index) ?: continue
            if (!value.isFinite()) {
                started = false
                continue
            }
            val x = xFor(index, geometry)
            val y = mapper(value)
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawDrawings(canvas: Canvas, geometry: Geometry) {
        drawings.forEach { drawing ->
            when (drawing.tool) {
                ChartTool.HORIZONTAL -> drawHorizontal(canvas, geometry, drawing)
                ChartTool.TRENDLINE -> drawTrendline(canvas, geometry, drawing.points)
                ChartTool.FIBONACCI -> drawFibonacci(canvas, geometry, drawing.points)
                ChartTool.TEXT -> drawTextDrawing(canvas, geometry, drawing)
                ChartTool.RULER -> drawRuler(canvas, geometry, drawing.points)
                ChartTool.CROSSHAIR -> Unit
            }
        }

        if (pendingPoints.isNotEmpty() && activeTool != ChartTool.CROSSHAIR) {
            val point = pendingPoints[0]
            val x1 = xFor(point.index, geometry)
            val y1 = priceY(point.price, geometry)
            canvas.drawCircle(x1, y1, dp(3f), drawingPaint)
            if (activeTool == ChartTool.TRENDLINE ||
                activeTool == ChartTool.FIBONACCI ||
                activeTool == ChartTool.RULER
            ) {
                canvas.drawLine(x1, y1, previewX, previewY, pendingPaint)
            }
        }
    }

    private fun drawHorizontal(canvas: Canvas, geometry: Geometry, drawing: ChartDrawing) {
        val point = drawing.points.firstOrNull() ?: return
        val y = priceY(point.price, geometry)
        canvas.drawLine(geometry.left, y, geometry.right, y, drawingPaint)
        drawPriceTag(canvas, geometry, y, point.price)
    }

    private fun drawTrendline(canvas: Canvas, geometry: Geometry, points: List<ChartPoint>) {
        if (points.size < 2) return
        val x1 = xFor(points[0].index, geometry)
        val y1 = priceY(points[0].price, geometry)
        val x2 = xFor(points[1].index, geometry)
        val y2 = priceY(points[1].price, geometry)
        canvas.drawLine(x1, y1, x2, y2, drawingPaint)
        canvas.drawCircle(x1, y1, dp(3f), drawingPaint)
        canvas.drawCircle(x2, y2, dp(3f), drawingPaint)
    }

    private fun drawFibonacci(canvas: Canvas, geometry: Geometry, points: List<ChartPoint>) {
        if (points.size < 2) return
        val x1 = xFor(points[0].index, geometry)
        val x2 = xFor(points[1].index, geometry)
        val high = max(points[0].price, points[1].price)
        val low = min(points[0].price, points[1].price)
        val levels = listOf(0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0)
        val left = min(x1, x2)
        val right = max(x1, x2)

        levels.forEach { level ->
            val price = high - (high - low) * level
            val y = priceY(price, geometry)
            drawingPaint.color = Color.argb(180, 255, 193, 7)
            canvas.drawLine(left, y, right, y, drawingPaint)
            labelPaint.color = Color.argb(210, 255, 213, 79)
            val percent = (level * 100).toInt()
            canvas.drawText(
                "$percent%  ${formatPrice(price)}",
                right + dp(3f),
                y + dp(3f),
                labelPaint
            )
        }
        drawingPaint.color = Color.rgb(255, 193, 7)
    }

    private fun drawTextDrawing(canvas: Canvas, geometry: Geometry, drawing: ChartDrawing) {
        val point = drawing.points.firstOrNull() ?: return
        val x = xFor(point.index, geometry)
        val y = priceY(point.price, geometry)
        labelPaint.color = drawingPaint.color
        canvas.drawText(drawing.text.ifBlank { "Note" }, x + dp(4f), y - dp(4f), labelPaint)
    }

    private fun drawRuler(canvas: Canvas, geometry: Geometry, points: List<ChartPoint>) {
        if (points.size < 2) return
        val x1 = xFor(points[0].index, geometry)
        val y1 = priceY(points[0].price, geometry)
        val x2 = xFor(points[1].index, geometry)
        val y2 = priceY(points[1].price, geometry)
        canvas.drawLine(x1, y1, x2, y2, drawingPaint)
        canvas.drawRect(
            min(x1, x2),
            min(y1, y2),
            max(x1, x2),
            max(y1, y2),
            drawingFillPaint
        )

        val delta = points[1].price - points[0].price
        val percent = if (points[0].price != 0.0) delta / points[0].price * 100.0 else 0.0
        labelPaint.color = drawingPaint.color
        val text = "${formatPrice(delta)}  (${String.format(Locale.US, "%.2f", percent)}%)"
        canvas.drawText(text, min(x1, x2) + dp(4f), min(y1, y2) - dp(4f), labelPaint)
    }

    private fun drawPriceTag(canvas: Canvas, geometry: Geometry, y: Float, price: Double) {
        val text = formatPrice(price)
        val textWidth = labelPaint.measureText(text)
        labelPaint.color = AppDesign.textPrimary
        canvas.drawRect(
            geometry.right + dp(2f),
            y - dp(8f),
            min(width.toFloat(), geometry.right + textWidth + dp(8f)),
            y + dp(8f),
            crosshairTagPaint
        )
        canvas.drawText(
            text,
            geometry.right + dp(4f),
            y + dp(3f),
            labelPaint
        )
    }

    private fun drawTimeAxis(canvas: Canvas, geometry: Geometry) {
        if (candles[geometry.start].timestamp <= 0L) return
        axisPaint.color = AppDesign.textMuted
        axisPaint.textAlign = Paint.Align.CENTER

        // Intraday series (any gap under a day) should show clock time; daily
        // and higher series show the date. Without this, an MCX 1m chart shows
        // the same "dd MMM" label three times, which looks like wrong spacing.
        var smallestGap = Long.MAX_VALUE
        for (index in geometry.start + 1 until geometry.end) {
            val gap = candles[index].timestamp - candles[index - 1].timestamp
            if (gap > 0L && gap < smallestGap) smallestGap = gap
        }
        val intraday = smallestGap < 86_400_000L
        val formatter = if (intraday) axisIntradayFormat else axisTimeFormat

        val indices = intArrayOf(geometry.start, (geometry.start + geometry.end) / 2, geometry.end - 1)
        indices.forEachIndexed { position, index ->
            val candle = candles.getOrNull(index) ?: return@forEachIndexed
            if (candle.timestamp <= 0L) return@forEachIndexed
            val x = xFor(index, geometry)
            val text = formatter.format(Date(candle.timestamp))
            axisPaint.textAlign = when (position) {
                0 -> Paint.Align.LEFT
                2 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(text, x, height - dp(4f), axisPaint)
        }
        axisPaint.textAlign = Paint.Align.RIGHT
    }

    private fun drawLegend(canvas: Canvas, geometry: Geometry) {
        val parts = ArrayList<String>()
        if (ChartIndicator.EMA_20 in indicators) parts.add("EMA 20")
        if (ChartIndicator.SMA_50 in indicators) parts.add("SMA 50")
        if (ChartIndicator.VWAP in indicators) parts.add("VWAP")
        if (ChartIndicator.BOLLINGER in indicators) parts.add("BB 20")
        if (ChartIndicator.SUPERTREND in indicators) parts.add("Supertrend")
        if (parts.isEmpty()) return

        labelPaint.color = AppDesign.textMuted
        canvas.drawText(
            parts.joinToString("  ·  "),
            geometry.left + dp(2f),
            geometry.priceTop + dp(10f),
            labelPaint
        )
    }

    private fun drawCrosshair(canvas: Canvas, geometry: Geometry) {
        if (!crosshairVisible) return

        val index = crosshairIndex.coerceIn(geometry.start, geometry.end - 1)
        val candle = candles.getOrNull(index) ?: return
        val x = xFor(index, geometry)
        val y = crosshairY.coerceIn(geometry.priceTop, geometry.priceBottom)

        canvas.drawLine(x, geometry.priceTop, x, geometry.bottom, crosshairPaint)
        canvas.drawLine(geometry.left, y, geometry.right, y, crosshairPaint)

        val price = priceAt(y, geometry)
        drawPriceTag(canvas, geometry, y, price)

        val timeText = if (candle.timestamp > 0L) {
            timeFormat.format(Date(candle.timestamp))
        } else {
            "#${index + 1}"
        }
        val timeWidth = tooltipTextPaint.measureText(timeText)
        canvas.drawRect(
            (x - timeWidth / 2f - dp(5f)).coerceIn(
                geometry.left,
                max(geometry.left, geometry.right - timeWidth - dp(10f))
            ),
            height - dp(18f),
            (x + timeWidth / 2f + dp(5f)).coerceIn(
                min(geometry.right, geometry.left + timeWidth + dp(10f)),
                width.toFloat()
            ),
            height.toFloat(),
            crosshairTagPaint
        )
        canvas.drawText(
            timeText,
            (x - timeWidth / 2f).coerceIn(geometry.left + dp(2f), max(geometry.left + dp(2f), geometry.right - timeWidth)),
            height - dp(5f),
            tooltipTextPaint
        )

        val info = "O ${formatPrice(candle.open)}  H ${formatPrice(candle.high)}  " +
            "L ${formatPrice(candle.low)}  C ${formatPrice(candle.close)}  V ${formatQuantity(candle.volume)}"
        val infoWidth = min(geometry.right - geometry.left - dp(8f), tooltipTextPaint.measureText(info) + dp(12f))
        val boxLeft = geometry.left + dp(2f)
        val boxTop = geometry.priceTop + dp(16f)
        canvas.drawRect(
            boxLeft,
            boxTop,
            boxLeft + infoWidth,
            boxTop + dp(17f),
            tooltipBackgroundPaint
        )
        canvas.drawText(
            info,
            boxLeft + dp(5f),
            boxTop + dp(12f),
            tooltipTextPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            crosshairVisible = false
            onCrosshairChanged?.invoke(null, -1)
            invalidate()
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                previewX = event.x
                previewY = event.y
                if (activeTool == ChartTool.CROSSHAIR) {
                    val geometry = computeGeometry()
                    if (geometry != null) {
                        crosshairVisible = true
                        crosshairX = event.x
                        crosshairY = event.y
                        crosshairIndex = indexAt(event.x, geometry)
                        onCrosshairChanged?.invoke(candles.getOrNull(crosshairIndex), crosshairIndex)
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                previewX = event.x
                previewY = event.y
                if (activeTool == ChartTool.CROSSHAIR) {
                    val geometry = computeGeometry()
                    if (geometry != null) {
                        // Crosshair drag only selects another candle.
                        // Keep the candle viewport fixed while inspecting data.
                        crosshairX = event.x
                        crosshairY = event.y
                        crosshairIndex = indexAt(event.x, geometry)
                        onCrosshairChanged?.invoke(candles.getOrNull(crosshairIndex), crosshairIndex)
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (activeTool != ChartTool.CROSSHAIR) {
                    handleDrawingTap(event.x, event.y)
                }
                crosshairVisible = false
                onCrosshairChanged?.invoke(null, -1)
                invalidate()
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                crosshairVisible = false
                onCrosshairChanged?.invoke(null, -1)
                previewX = 0f
                previewY = 0f
                invalidate()
                return true
            }
        }
        return true
    }

    private fun handleDrawingTap(x: Float, y: Float) {
        val geometry = computeGeometry() ?: return
        val index = indexAt(x, geometry)
        var price = priceAt(y, geometry)
        if (magnetEnabled) {
            price = snapPrice(price, index)
        }
        val point = ChartPoint(index, price)

        when (activeTool) {
            ChartTool.HORIZONTAL -> {
                drawings.add(ChartDrawing(ChartTool.HORIZONTAL, mutableListOf(point)))
            }

            ChartTool.TEXT -> {
                val callback = onTextDrawingRequested
                if (callback != null) {
                    callback(index, price)
                } else {
                    addTextDrawing(index, price, "Note")
                }
            }

            ChartTool.TRENDLINE,
            ChartTool.FIBONACCI,
            ChartTool.RULER -> {
                pendingPoints.add(point)
                if (pendingPoints.size >= 2) {
                    drawings.add(
                        ChartDrawing(
                            tool = activeTool,
                            points = pendingPoints.toMutableList()
                        )
                    )
                    pendingPoints.clear()
                }
            }

            ChartTool.CROSSHAIR -> Unit
        }
    }

    private fun snapPrice(price: Double, index: Int): Double {
        val candle = candles.getOrNull(index) ?: return price
        val candidates = listOf(candle.open, candle.high, candle.low, candle.close)
        return candidates.minByOrNull { abs(it - price) } ?: price
    }

    private fun formatPrice(value: Double): String {
        if (!value.isFinite()) return "—"
        val decimals = when {
            abs(value) >= 1000.0 -> 2
            abs(value) >= 1.0 -> 2
            else -> 4
        }
        return String.format(Locale.US, "%.${decimals}f", value)
    }

    private fun formatQuantity(value: Long): String {
        return when {
            value >= 10_000_000L -> String.format(Locale.US, "%.2fCr", value / 10_000_000.0)
            value >= 100_000L -> String.format(Locale.US, "%.2fL", value / 100_000.0)
            value >= 1_000L -> String.format(Locale.US, "%.1fK", value / 1_000.0)
            else -> value.toString()
        }
    }

    private fun dp(value: Float): Float = value * density

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private data class SubChartArea(
        val indicator: ChartIndicator,
        val top: Float,
        val bottom: Float
    )

    private data class Geometry(
        val left: Float,
        val right: Float,
        val top: Float,
        val bottom: Float,
        val priceTop: Float,
        val priceBottom: Float,
        val volumeTop: Float,
        val volumeBottom: Float,
        val start: Int,
        val end: Int,
        val visible: Int,
        val slot: Float,
        val minPrice: Double,
        val maxPrice: Double,
        val subCharts: List<SubChartArea>
    )

    private companion object {
        const val MIN_VISIBLE = 6f
    }
}
