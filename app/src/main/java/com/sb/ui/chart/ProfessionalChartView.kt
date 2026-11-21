package com.sb.ui.chart

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import com.sb.backend.market.TimeframeBuckets
import com.sb.ui.theme.AppDesign
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

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
            removeCallbacks(crosshairHoldRunnable)
            recycleVelocityTracker()
            cancelAnimations()
            isTouchDown = false
            crosshairHoldActivated = false
            isPanning = false
            isPricePanning = false
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
            invalidate()
        }

    var timeframeKey: String = ""
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var priceScaleMode: PriceScaleMode = PriceScaleMode.ABSOLUTE
        set(value) {
            field = value
            relativeReference = Double.NaN
            invalidate()
        }

    private var relativeReference = Double.NaN

    var lastPrice: Double? = null
        set(value) {
            val next = value?.takeIf { PriceScaleEngine.isUsablePrice(it) }
            if (field == next) return
            field = next
            if (next != null) {
                startLastPricePulse()
            } else {
                lastPricePulseAnimator?.cancel()
                lastPricePulseAnimator = null
                lastPricePulse = 0f
            }
            invalidate()
        }

    var previousClose: Double? = null
        set(value) {
            val next = value?.takeIf { PriceScaleEngine.isUsablePrice(it) }
            if (field == next) return
            field = next
            invalidate()
        }

    var onCrosshairChanged: ((ChartCandle?, Int) -> Unit)? = null
    var onTextDrawingRequested: ((Int, Double) -> Unit)? = null

    private var priceDecimals = 2

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
    private var lastTouchY = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isTouchDown = false
    private var crosshairHoldActivated = false
    private var isPanning = false
    private var isPricePanning = false
    private var drawingStartValid = false

    private val viewConfiguration = ViewConfiguration.get(context)
    private val touchSlop = viewConfiguration.scaledTouchSlop
    private val minFlingVelocity = viewConfiguration.scaledMinimumFlingVelocity

    private var previewX = 0f
    private var previewY = 0f
    private var lastGeometry: Geometry? = null

    private val crosshairHoldRunnable = Runnable {
        if (!isTouchDown) {
            return@Runnable
        }

        val geometry = computeGeometry() ?: return@Runnable

        if (!isInsideChartArea(previewX, previewY, geometry)) {
            return@Runnable
        }

        crosshairHoldActivated = true
        isPanning = false
        isPricePanning = false

        crosshairVisible = true
        crosshairX = previewX.coerceIn(geometry.left, geometry.right)
        crosshairY = previewY.coerceIn(
            geometry.priceTop,
            geometry.priceBottom
        )

        crosshairIndex = indexAt(crosshairX, geometry)

        onCrosshairChanged?.invoke(
            source.getOrNull(crosshairIndex),
            crosshairIndex
        )

        invalidate()
    }

    private var manualPriceRange: PriceRange? = null
    private var horizontalOverscroll = 0f
    private var panRawOffset = 0f
    private var isAxisZooming = false
    private var axisStartY = 0f
    private var axisLastY = 0f
    private var axisStartRange = PriceRange(0.0, 1.0)
    private var axisAnchorPrice = 0.0
    private var axisAnchorRatio = 0.5
    private var axisMinSpan = 0.0
    private var axisMaxSpan = 0.0
    private var suppressNextAxisDown = false
    private var velocityTracker: VelocityTracker? = null
    private var scrollAnimator: ValueAnimator? = null
    private var priceAnimator: ValueAnimator? = null
    private var lastPricePulseAnimator: ValueAnimator? = null
    private var lastPricePulse = 0f

    private val indicatorCache = IndicatorIncrementalCache()
    private var ema20: DoubleArray = DoubleArray(0)
    private var sma50: DoubleArray = DoubleArray(0)
    private var vwap: DoubleArray = DoubleArray(0)
    private var rsi14: DoubleArray = DoubleArray(0)
    private var macdResult: IndicatorMath.MacdResult? = null
    private var bollingerResult: IndicatorMath.BollingerResult? = null
    private var supertrendResult: IndicatorMath.SupertrendResult? = null

    private val density = context.resources.displayMetrics.density
    private val timeFormat = SimpleDateFormat("EEEE · dd MMM yyyy · hh:mm a", Locale.US).apply {
        timeZone = TimeframeBuckets.IST
    }
    private val axisTimeFormat = SimpleDateFormat("dd MMM yyyy", Locale.US).apply {
        timeZone = TimeframeBuckets.IST
    }
    private val axisIntradayFormat = SimpleDateFormat("HH:mm", Locale.US).apply {
        timeZone = TimeframeBuckets.IST
    }
    private val axisIntradayWithDateFormat = SimpleDateFormat("dd MMM HH:mm", Locale.US).apply {
        timeZone = TimeframeBuckets.IST
    }

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
    private val ltpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1.1f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(3f)), 0f)
    }
    private val referenceLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppDesign.textMuted
        strokeWidth = dp(0.9f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
    }
    private val lastPriceHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val doubleTapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(event: MotionEvent): Boolean {
                val geometry = computeGeometry() ?: return false
                if (!isInsidePriceAxis(event.x, event.y, geometry)) return false
                resetPriceScale()
                suppressNextAxisDown = true
                return true
            }
        }
    ).apply {
        setIsLongpressEnabled(false)
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (candles.isEmpty()) return false

                val geometry = computeGeometry() ?: return false

                val focusX = detector.focusX.coerceIn(
                    geometry.left,
                    geometry.right
                )
                val anchorIndex = geometry.startFloat +
                    (focusX - geometry.left - horizontalOverscroll) / geometry.slot

                val maxLimit = max(MIN_VISIBLE, candles.size.toFloat())
                val newVisibleCount = (visibleCount / detector.scaleFactor)
                    .coerceIn(MIN_VISIBLE, maxLimit)
                if (newVisibleCount == visibleCount) return true

                val effectiveVisible = min(newVisibleCount, candles.size.toFloat())
                    .coerceAtLeast(1f)
                val newSlot = (geometry.right - geometry.left) / effectiveVisible
                visibleCount = newVisibleCount
                scrollOffset = PriceScaleEngine.anchoredStart(
                    anchorIndex = anchorIndex,
                    focusX = focusX,
                    left = geometry.left,
                    newSlot = newSlot,
                    candleCount = candles.size,
                    newVisible = effectiveVisible
                )

                followLatest = false
                invalidate()
                return true
            }
        }
    )

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
        val nextSource = ChartCandleSeries.canonicalize(data)
        val oldSource = source

        if (!sameSource(oldSource, nextSource)) {
            if (preserveViewport && oldSource.isNotEmpty() && nextSource.isNotEmpty()) {

                fun remapIndex(oldIndex: Int): Int {
                    val timestamp = oldSource.getOrNull(oldIndex)?.timestamp
                        ?: return -1

                    return nextSource.indexOfFirst {
                        it.timestamp == timestamp
                    }
                }

                if (crosshairIndex >= 0) {
                    val mapped = remapIndex(crosshairIndex)
                    if (mapped >= 0) {
                        crosshairIndex = mapped
                    } else {
                        crosshairIndex = -1
                        crosshairVisible = false
                    }
                }

                val remappedDrawings = drawings.mapNotNull { drawing ->
                    val mappedPoints = drawing.points.mapNotNull { point ->
                        val mappedIndex = remapIndex(point.index)
                        if (mappedIndex < 0) null
                        else ChartPoint(mappedIndex, point.price)
                    }

                    if (mappedPoints.size != drawing.points.size) {
                        null
                    } else {
                        drawing.copy(points = mappedPoints.toMutableList())
                    }
                }

                drawings.clear()
                drawings.addAll(remappedDrawings)

                val remappedPending = pendingPoints.mapNotNull { point ->
                    val mappedIndex = remapIndex(point.index)
                    if (mappedIndex < 0) null
                    else ChartPoint(mappedIndex, point.price)
                }

                pendingPoints.clear()
                pendingPoints.addAll(remappedPending)
            } else if (preserveViewport) {
                crosshairVisible = false
                crosshairIndex = -1
                drawings.clear()
                pendingPoints.clear()
            }

            source = nextSource
        }

        chartStyle = style

        if (!preserveViewport) {
            crosshairVisible = false
            crosshairIndex = -1
            visibleCount = 40f
            followLatest = true
            drawings.clear()
            pendingPoints.clear()
            scrollOffset = 0f
            horizontalOverscroll = 0f
            manualPriceRange = null
            cancelAnimations()
        }

        relativeReference = Double.NaN

        if (followLatest) {
            scrollOffset = PriceScaleEngine.maxScroll(candles.size, visibleCount)
        }

        rebuildDisplayCandles()
        invalidate()
    }

    /** Move the viewport to the newest available candles without clearing drawings. */
    fun scrollToLatest() {
        scrollAnimator?.cancel()
        scrollAnimator = null
        followLatest = true
        scrollOffset = PriceScaleEngine.maxScroll(candles.size, visibleCount)
        horizontalOverscroll = 0f
        crosshairVisible = false
        crosshairIndex = -1
        invalidate()
    }

    fun resetPriceScale() {
        priceAnimator?.cancel()
        priceAnimator = null
        manualPriceRange = null
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
        if (followLatest) {
            scrollOffset = PriceScaleEngine.maxScroll(candles.size, visibleCount)
        }
    }

    private fun sameSource(a: List<ChartCandle>, b: List<ChartCandle>): Boolean {
        if (a.size != b.size) return false
        for (index in a.indices) {
            val first = a[index]
            val second = b[index]
            if (first.timestamp != second.timestamp ||
                first.open != second.open ||
                first.high != second.high ||
                first.low != second.low ||
                first.close != second.close ||
                first.volume != second.volume
            ) {
                return false
            }
        }
        return true
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
        val calculated = indicatorCache.update(source, indicators, timeframeKey)
        ema20 = calculated.ema20
        sma50 = calculated.sma50
        vwap = calculated.vwap
        rsi14 = calculated.rsi14
        macdResult = calculated.macd
        bollingerResult = calculated.bollinger
        supertrendResult = calculated.supertrend
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
        updateRelativeReference(geometry)

        drawPriceGrid(canvas, geometry)
        drawVolume(canvas, geometry)
        drawPriceSeries(canvas, geometry)
        drawOverlays(canvas, geometry)
        drawLastPriceRay(canvas, geometry)
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

        val candleRange = PriceScaleEngine.candleRange(
            scrollOffset = scrollOffset,
            visibleCount = visibleCount,
            candleCount = candles.size,
            left = left,
            right = right
        ) ?: return null

        val subIndicators = ArrayList<ChartIndicator>()
        if (ChartIndicator.RSI_14 in indicators) subIndicators.add(ChartIndicator.RSI_14)
        if (ChartIndicator.MACD in indicators) subIndicators.add(ChartIndicator.MACD)

        val layout = PriceScaleEngine.verticalLayout(
            top = top,
            bottom = bottom,
            subCount = subIndicators.size,
            minPanel = dp(MIN_PANEL_HEIGHT_DP),
            gap = dp(2f),
            minMain = dp(MIN_MAIN_HEIGHT_DP),
            minVolume = dp(MIN_VOLUME_HEIGHT_DP)
        ) ?: return null

        val autoRange = computeAutoPriceRange(candleRange.start, candleRange.end)
        val range = manualPriceRange
            ?.takeIf { it.isValid() }
            ?: autoRange

        val subCharts = layout.panels.mapNotNull { panel ->
            subIndicators.getOrNull(panel.index)?.let { indicator ->
                SubChartArea(indicator, panel.top, panel.bottom)
            }
        }

        return Geometry(
            left = left,
            right = right,
            top = top,
            bottom = bottom,
            priceTop = layout.priceTop,
            priceBottom = layout.priceBottom,
            volumeTop = layout.volumeTop,
            volumeBottom = layout.volumeBottom,
            start = candleRange.start,
            end = candleRange.end,
            visible = max(1, candleRange.end - candleRange.start),
            slot = candleRange.slot,
            range = candleRange,
            startFloat = candleRange.startFloat,
            visibleFloat = candleRange.visibleFloat,
            minPrice = range.min,
            maxPrice = range.max,
            subCharts = subCharts
        )
    }

    private fun computeAutoPriceRange(start: Int, end: Int): PriceRange {
        var maxPrice = Double.NEGATIVE_INFINITY
        var minPrice = Double.POSITIVE_INFINITY

        for (index in start until end) {
            val candle = candles[index]
            if (!candle.high.isFinite() || !candle.low.isFinite()) continue
            if (!PriceScaleEngine.isUsablePrice(candle.high)) continue
            if (!PriceScaleEngine.isUsablePrice(candle.low)) continue
            if (candle.high > maxPrice) maxPrice = candle.high
            if (candle.low < minPrice) minPrice = candle.low
        }

        fun includeOverlay(values: DoubleArray?) {
            if (values == null || values.isEmpty()) return
            for (index in start until end) {
                val value = values.getOrNull(index) ?: continue
                if (!PriceScaleEngine.isUsablePrice(value)) continue
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

        if (end >= candles.size) {
            lastPrice?.let {
                if (PriceScaleEngine.isUsablePrice(it)) {
                    if (it > maxPrice) maxPrice = it
                    if (it < minPrice) minPrice = it
                }
            }
        }

        val reference = if (end >= candles.size) {
            lastPrice ?: Double.NaN
        } else {
            Double.NaN
        }
        return PriceScaleEngine.autoFitRange(minPrice, maxPrice, reference)
    }

    private fun priceY(price: Double, geometry: Geometry): Float {
        return PriceScaleEngine.priceToY(
            price,
            PriceRange(geometry.minPrice, geometry.maxPrice),
            geometry.priceTop,
            geometry.priceBottom
        )
    }

    private fun xFor(index: Int, geometry: Geometry): Float {
        return PriceScaleEngine.xFor(
            index = index,
            range = geometry.range,
            left = geometry.left,
            overscroll = horizontalOverscroll
        )
    }

    private fun indexAt(x: Float, geometry: Geometry): Int {
        return PriceScaleEngine.indexAt(
            x = x,
            range = geometry.range,
            left = geometry.left,
            overscroll = horizontalOverscroll
        )
    }

    private fun isInsidePricePlotArea(
        x: Float,
        y: Float,
        geometry: Geometry
    ): Boolean {
        return PriceScaleEngine.isInsidePlot(
            x = x,
            y = y,
            left = geometry.left,
            right = geometry.right,
            top = geometry.priceTop,
            bottom = geometry.priceBottom
        )
    }

    private fun isInsideChartArea(
        x: Float,
        y: Float,
        geometry: Geometry
    ): Boolean {
        return x >= geometry.left &&
            x <= geometry.right &&
            y >= geometry.top &&
            y <= geometry.bottom
    }

    private fun isInsidePriceAxis(
        x: Float,
        y: Float,
        geometry: Geometry
    ): Boolean {
        return x >= geometry.right &&
            x <= width.toFloat() &&
            y >= geometry.priceTop &&
            y <= geometry.priceBottom
    }

    private fun priceAt(y: Float, geometry: Geometry): Double {
        return PriceScaleEngine.priceForY(
            y,
            PriceRange(geometry.minPrice, geometry.maxPrice),
            geometry.priceTop,
            geometry.priceBottom
        )
    }

    private fun timeGridIndices(geometry: Geometry): List<Int> {
        val last = geometry.end - 1
        if (last < geometry.start) return emptyList()
        val step = (last - geometry.start).toFloat() / 3f
        return (0..3).map { position ->
            (geometry.start + round(position * step)).toInt().coerceIn(geometry.start, last)
        }.distinct()
    }

    private fun drawVerticalGrid(
        canvas: Canvas,
        geometry: Geometry,
        top: Float,
        bottom: Float
    ) {
        if (bottom <= top) return
        val saveCount = canvas.save()
        canvas.clipRect(geometry.left, top, geometry.right, bottom)
        timeGridIndices(geometry).forEach { index ->
            val x = xFor(index, geometry)
            if (x < geometry.left || x > geometry.right) return@forEach
            canvas.drawLine(x, top, x, bottom, gridPaint)
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawPriceGrid(canvas: Canvas, geometry: Geometry) {
        axisPaint.color = AppDesign.textMuted
        gridPaint.color = AppDesign.divider

        val priceHeight = geometry.priceBottom - geometry.priceTop
        val labelSpacing = axisPaint.textSize + dp(5f)
        val maxTicks = max(
            2,
            min(12, (priceHeight / labelSpacing).toInt() + 1)
        )
        val tickSet = PriceScaleEngine.ticks(
            PriceRange(geometry.minPrice, geometry.maxPrice),
            maxTicks
        )

        val saveCount = canvas.save()
        canvas.clipRect(
            geometry.left,
            geometry.priceTop,
            geometry.right,
            geometry.priceBottom
        )

        tickSet.ticks.forEach { tick ->
            val y = priceY(tick.price, geometry)
            if (y < geometry.priceTop - dp(1f) || y > geometry.priceBottom + dp(1f)) {
                return@forEach
            }
            canvas.drawLine(
                geometry.left,
                y,
                geometry.right,
                y,
                gridPaint
            )
        }

        if (priceScaleMode == PriceScaleMode.RELATIVE) {
            val reference = relativeReference.takeIf { isUsableRelativeReference(it) }
            if (reference != null) {
                val y = priceY(reference, geometry)
                if (y >= geometry.priceTop && y <= geometry.priceBottom) {
                    canvas.drawLine(
                        geometry.left,
                        y,
                        geometry.right,
                        y,
                        referenceLinePaint
                    )
                }
            }
        }

        canvas.restoreToCount(saveCount)
        drawVerticalGrid(canvas, geometry, geometry.priceTop, geometry.priceBottom)

        priceDecimals = tickSet.decimals
        val ltpPrice = lastPrice?.takeIf {
            it >= geometry.minPrice && it <= geometry.maxPrice
        }
        val ltpY = ltpPrice?.let { priceY(it, geometry) }?.takeIf {
            it >= geometry.priceTop && it <= geometry.priceBottom
        }
        val previousClose = previousCloseForLastPrice()
        val ltpColor = if (ltpPrice != null && previousClose != null &&
            ltpPrice >= previousClose
        ) {
            bullishColor
        } else {
            bearishColor
        }
        val pulse = lastPricePulse.coerceIn(0f, 1f)
        val highlightAlpha = (55f + 120f * pulse).toInt().coerceIn(0, 255)

        var lastLabelY = Float.NEGATIVE_INFINITY
        tickSet.ticks.asReversed().forEach { tick ->
            val y = priceY(tick.price, geometry)
            if (y < geometry.priceTop - dp(2f) || y > geometry.priceBottom + dp(2f)) {
                return@forEach
            }
            if (ltpY != null && abs(y - ltpY) < labelSpacing) return@forEach
            if (y - lastLabelY < labelSpacing) return@forEach
            lastLabelY = y

            val text = if (priceScaleMode == PriceScaleMode.RELATIVE &&
                isUsableRelativeReference(relativeReference)
            ) {
                formatPrice(tick.price)
            } else {
                tick.label
            }

            canvas.drawText(
                text,
                width - dp(4f),
                y + dp(3.5f),
                axisPaint
            )
        }

        ltpPrice?.let { price ->
            val y = ltpY ?: return@let
            lastPriceHighlightPaint.color = Color.argb(
                highlightAlpha,
                Color.red(ltpColor),
                Color.green(ltpColor),
                Color.blue(ltpColor)
            )
            canvas.drawRect(
                geometry.right + dp(1f),
                y - dp(7f),
                width.toFloat(),
                y + dp(7f),
                lastPriceHighlightPaint
            )
            axisPaint.color = ltpColor
            axisPaint.isFakeBoldText = true
            val label = if (priceScaleMode == PriceScaleMode.RELATIVE &&
                !isUsableRelativeReference(relativeReference)
            ) {
                PriceScaleEngine.formatPrice(price, priceDecimals)
            } else {
                formatPrice(price)
            }
            canvas.drawText(
                label,
                width - dp(4f),
                y + dp(3.5f),
                axisPaint
            )
            axisPaint.isFakeBoldText = false
            axisPaint.color = AppDesign.textMuted
        }
    }

    private fun drawVolume(canvas: Canvas, geometry: Geometry) {
        val saveCount = canvas.save()

        canvas.clipRect(
            geometry.left,
            geometry.volumeTop,
            geometry.right,
            geometry.volumeBottom
        )

        var maxVolume = 0L

        for (index in geometry.start until geometry.end) {
            val volume = candles[index].volume
            if (volume > maxVolume) maxVolume = volume
        }

        val candleWidth = min(
            dp(12f),
            geometry.slot * 0.72f
        ).coerceAtLeast(dp(0.5f))

        if (maxVolume > 0L) {
            for (index in geometry.start until geometry.end) {
                val candle = candles[index]
                if (candle.volume <= 0L) continue

                val x = xFor(index, geometry)
                val barHeight =
                    (candle.volume.toDouble() / maxVolume.toDouble()).toFloat() *
                        (geometry.volumeBottom - geometry.volumeTop)

                val color = if (candle.close >= candle.open) {
                    bullishColor
                } else {
                    bearishColor
                }

                volumePaint.color = Color.argb(
                    95,
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                )

                canvas.drawRect(
                    x - candleWidth / 2f,
                    (geometry.volumeBottom - barHeight).coerceAtLeast(geometry.volumeTop),
                    x + candleWidth / 2f,
                    geometry.volumeBottom,
                    volumePaint
                )
            }
        }

        canvas.drawLine(
            geometry.left,
            geometry.volumeTop,
            geometry.right,
            geometry.volumeTop,
            gridPaint
        )

        drawVerticalGrid(
            canvas,
            geometry,
            geometry.volumeTop,
            geometry.volumeBottom
        )

        canvas.restoreToCount(saveCount)
    }

    private fun drawPriceSeries(canvas: Canvas, geometry: Geometry) {
        when (chartStyle) {
            ChartStyle.LINE -> drawLineSeries(canvas, geometry)
            ChartStyle.CANDLE, ChartStyle.HEIKIN_ASHI -> drawCandles(canvas, geometry)
            ChartStyle.BAR -> drawBars(canvas, geometry)
        }
    }

    private fun drawBars(canvas: Canvas, geometry: Geometry) {

        // Calculate OHLC tick width according to candle spacing.
        val tickWidth = min(
            dp(6f),
            geometry.slot * 0.35f
        ).coerceAtLeast(dp(0.5f))

        // Restrict OHLC drawing to the price chart area.
        val saveCount = canvas.save()

        canvas.clipRect(
            geometry.left,
            geometry.priceTop,
            geometry.right,
            geometry.priceBottom
        )

        for (index in geometry.start until geometry.end) {

            val candle = candles[index]

            // Skip invalid OHLC data.
            if (!candle.open.isFinite() ||
                !candle.high.isFinite() ||
                !candle.low.isFinite() ||
                !candle.close.isFinite()
            ) {
                continue
            }

            val x = xFor(index, geometry)

            val color = if (candle.close >= candle.open) {
                bullishColor
            } else {
                bearishColor
            }

            wickPaint.color = color

            val highY = priceY(candle.high, geometry)
            val lowY = priceY(candle.low, geometry)
            val openY = priceY(candle.open, geometry)
            val closeY = priceY(candle.close, geometry)

            // 1. High-Low vertical line.
            canvas.drawLine(
                x,
                highY,
                x,
                lowY,
                wickPaint
            )

            // 2. Open tick on the left.
            canvas.drawLine(
                x - tickWidth / 2f,
                openY,
                x,
                openY,
                wickPaint
            )

            // 3. Close tick on the right.
            canvas.drawLine(
                x,
                closeY,
                x + tickWidth / 2f,
                closeY,
                wickPaint
            )
        }

        // Restore the original canvas clipping.
        canvas.restoreToCount(saveCount)
    }

    private fun drawCandles(canvas: Canvas, geometry: Geometry) {
        val candleWidth = min(
            dp(12f),
            geometry.slot * 0.72f
        ).coerceAtLeast(dp(0.5f))

        // Keep candle bodies and wicks inside the price chart area.
        val saveCount = canvas.save()
        canvas.clipRect(
            geometry.left,
            geometry.priceTop,
            geometry.right,
            geometry.priceBottom
        )

        for (index in geometry.start until geometry.end) {
            val candle = candles[index]

            // Ignore invalid OHLC values.
            if (!candle.open.isFinite() ||
                !candle.high.isFinite() ||
                !candle.low.isFinite() ||
                !candle.close.isFinite()
            ) {
                continue
            }

            val x = xFor(index, geometry)

            val isBullish = candle.close >= candle.open
            val color = if (isBullish) bullishColor else bearishColor

            val highY = priceY(candle.high, geometry)
            val lowY = priceY(candle.low, geometry)
            val openY = priceY(candle.open, geometry)
            val closeY = priceY(candle.close, geometry)

            // Draw the complete high-low wick first.
            wickPaint.color = color

            canvas.drawLine(
                x,
                highY,
                x,
                lowY,
                wickPaint
            )

            // Candle body must represent the exact Open-Close price distance.
            // Do not apply artificial minimum height.
            val bodyTop = min(openY, closeY)
            val bodyBottom = max(openY, closeY)

            candlePaint.color = color

            // A true Doji has zero body height.
            // Its price is still represented by the wick.
            if (bodyBottom > bodyTop) {
                canvas.drawRect(
                    x - candleWidth / 2f,
                    bodyTop,
                    x + candleWidth / 2f,
                    bodyBottom,
                    candlePaint
                )
            }
        }

        canvas.restoreToCount(saveCount)
    }

    private fun drawLineSeries(canvas: Canvas, geometry: Geometry) {
        val path = Path()
        val fillPath = Path()
        var inSegment = false
        var segmentLastX = 0f
        var hasPoints = false

        for (index in geometry.start until geometry.end) {
            val close = candles[index].close
            if (!close.isFinite()) {
                if (inSegment) {
                    fillPath.lineTo(segmentLastX, geometry.priceBottom)
                    fillPath.close()
                    inSegment = false
                }
                continue
            }

            val x = xFor(index, geometry)
            val y = priceY(close, geometry)
            if (!inSegment) {
                path.moveTo(x, y)
                fillPath.moveTo(x, geometry.priceBottom)
                fillPath.lineTo(x, y)
                inSegment = true
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            segmentLastX = x
            hasPoints = true
        }

        if (!hasPoints) return
        if (inSegment) {
            fillPath.lineTo(segmentLastX, geometry.priceBottom)
            fillPath.close()
        }
        lineFillPaint.shader = LinearGradient(
            0f,
            geometry.priceTop,
            0f,
            geometry.priceBottom,
            Color.argb(80, 56, 189, 248),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        val saveCount = canvas.save()
        canvas.clipRect(
            geometry.left,
            geometry.priceTop,
            geometry.right,
            geometry.priceBottom
        )
        canvas.drawPath(fillPath, lineFillPaint)
        canvas.drawPath(path, linePaint)
        canvas.restoreToCount(saveCount)
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

    private fun drawLastPriceRay(canvas: Canvas, geometry: Geometry) {
        val price = lastPrice ?: return
        if (!PriceScaleEngine.isUsablePrice(price)) return
        if (price < geometry.minPrice || price > geometry.maxPrice) return

        val y = priceY(price, geometry)
        if (y < geometry.priceTop || y > geometry.priceBottom) return

        val reference = previousCloseForLastPrice()
        val isBullish = reference == null || price >= reference
        val color = if (isBullish) bullishColor else bearishColor

        val saveCount = canvas.save()
        canvas.clipRect(
            geometry.left,
            geometry.priceTop,
            geometry.right,
            geometry.priceBottom
        )
        ltpPaint.color = color
        canvas.drawLine(geometry.left, y, geometry.right, y, ltpPaint)
        canvas.restoreToCount(saveCount)
    }

    private fun startLastPricePulse() {
        lastPricePulseAnimator?.cancel()
        lastPricePulseAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 650L
            addUpdateListener { animation ->
                lastPricePulse = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun drawSupertrend(
        canvas: Canvas,
        result: IndicatorMath.SupertrendResult,
        geometry: Geometry
    ) {
        var previousX = Float.NaN
        var previousY = Float.NaN
        var previousDirection = 0
        val saveCount = canvas.save()
        canvas.clipRect(
            geometry.left,
            geometry.priceTop,
            geometry.right,
            geometry.priceBottom
        )

        for (index in geometry.start until geometry.end) {
            val value = result.line.getOrNull(index) ?: break
            val direction = result.direction.getOrNull(index) ?: break
            if (!value.isFinite() || direction == 0) {
                previousX = Float.NaN
                previousY = Float.NaN
                previousDirection = 0
                continue
            }

            val x = xFor(index, geometry)
            val y = priceY(value, geometry)

            // Do not connect opposite trend segments across a direction flip.
            if (previousX.isFinite() && previousY.isFinite() && direction == previousDirection) {
                val paint = if (direction >= 0) {
                    supertrendUpPaint
                } else {
                    supertrendDownPaint
                }
                canvas.drawLine(previousX, previousY, x, y, paint)
            }

            previousX = x
            previousY = y
            previousDirection = direction
        }
        canvas.restoreToCount(saveCount)
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
        val saveCount = canvas.save()
        canvas.clipRect(
            geometry.left,
            geometry.priceTop,
            geometry.right,
            geometry.priceBottom
        )
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
        canvas.restoreToCount(saveCount)
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
            if (height >= dp(38f)) {
                labelPaint.color = AppDesign.textMuted
                canvas.drawText(level.toInt().toString(), geometry.left + dp(2f), y - dp(2f), labelPaint)
            }
        }
        gridPaint.color = AppDesign.divider
        drawVerticalGrid(canvas, geometry, area.top, area.bottom)

        val saveCount = canvas.save()

        canvas.clipRect(
            geometry.left,
            area.top,
            geometry.right,
            area.bottom
        )

        val path = Path()
        var started = false

        for (index in geometry.start until geometry.end) {
            val value = rsi14.getOrNull(index) ?: continue

            if (!value.isFinite()) {
                started = false
                continue
            }

            val x = xFor(index, geometry)
            val y = rsiY(value).coerceIn(area.top, area.bottom)

            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }

        canvas.drawPath(path, rsiPaint)
        canvas.restoreToCount(saveCount)

        val lastValue = rsi14.getOrNull(geometry.end - 1)
        labelPaint.color = rsiPaint.color
        val text = if (lastValue != null && lastValue.isFinite()) {
            "RSI 14  ${String.format(Locale.US, "%.1f", lastValue)}"
        } else {
            "RSI 14"
        }
        val previousAlign = labelPaint.textAlign
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(text, geometry.right - dp(2f), area.top + dp(10f), labelPaint)
        labelPaint.textAlign = previousAlign
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
            // Histogram bars share this axis; include them to prevent clipping.
            result.histogram.getOrNull(index)?.takeIf { it.isFinite() }?.let {
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
        drawVerticalGrid(canvas, geometry, area.top, area.bottom)

        val saveCount = canvas.save()

        canvas.clipRect(
            geometry.left,
            area.top,
            geometry.right,
            area.bottom
        )

        val candleWidth = max(
            dp(1f),
            min(dp(10f), geometry.slot * 0.45f)
        )

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
                min(zeroY, y).coerceIn(area.top, area.bottom),
                x + candleWidth / 2f,
                max(zeroY, y).coerceIn(area.top, area.bottom),
                histogramPaint
            )
        }

        drawMacdSeries(canvas, result.macd, geometry, macdPaint, ::macdY)
        drawMacdSeries(canvas, result.signal, geometry, signalPaint, ::macdY)

        canvas.restoreToCount(saveCount)

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
        val area = geometry.subCharts.firstOrNull {
            it.indicator == ChartIndicator.MACD
        } ?: return

        val saveCount = canvas.save()

        canvas.clipRect(
            geometry.left,
            area.top,
            geometry.right,
            area.bottom
        )

        val path = Path()
        var started = false

        for (index in geometry.start until geometry.end) {
            val value = values.getOrNull(index) ?: continue

            if (!value.isFinite()) {
                started = false
                continue
            }

            val x = xFor(index, geometry)
            val y = mapper(value).coerceIn(area.top, area.bottom)

            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }

        canvas.drawPath(path, paint)
        canvas.restoreToCount(saveCount)
    }

    private fun drawDrawings(canvas: Canvas, geometry: Geometry) {
        val saveCount = canvas.save()
        canvas.clipRect(
            geometry.left,
            geometry.priceTop,
            geometry.right,
            geometry.priceBottom
        )

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
                canvas.drawLine(
                    x1,
                    y1,
                    previewX.coerceIn(geometry.left, geometry.right),
                    previewY.coerceIn(geometry.priceTop, geometry.priceBottom),
                    pendingPaint
                )
            }
        }

        canvas.restoreToCount(saveCount)

        drawings.forEach { drawing ->
            if (drawing.tool != ChartTool.HORIZONTAL) return@forEach
            val point = drawing.points.firstOrNull() ?: return@forEach
            val y = priceY(point.price, geometry)
            if (y >= geometry.priceTop && y <= geometry.priceBottom) {
                drawPriceTag(canvas, geometry, y, point.price)
            }
        }
    }

    private fun drawHorizontal(canvas: Canvas, geometry: Geometry, drawing: ChartDrawing) {
        val point = drawing.points.firstOrNull() ?: return
        val y = priceY(point.price, geometry)
        canvas.drawLine(geometry.left, y, geometry.right, y, drawingPaint)
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
                "$percent%  ${formatAbsolutePrice(price)}",
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
        val percent = if (abs(points[0].price) > RELATIVE_REFERENCE_EPS) {
            delta / abs(points[0].price) * 100.0
        } else {
            0.0
        }
        labelPaint.color = drawingPaint.color
        val text = "${formatAbsolutePrice(delta)}  (${String.format(Locale.US, "%.2f", percent)}%)"
        canvas.drawText(text, min(x1, x2) + dp(4f), min(y1, y2) - dp(4f), labelPaint)
    }

    private fun drawPriceTag(canvas: Canvas, geometry: Geometry, y: Float, price: Double) {
        drawAxisTag(
            canvas = canvas,
            geometry = geometry,
            y = y,
            text = formatPrice(price),
            background = crosshairTagPaint,
            textColor = AppDesign.textPrimary
        )
    }

    private fun drawAxisTag(
        canvas: Canvas,
        geometry: Geometry,
        y: Float,
        text: String,
        background: Paint,
        textColor: Int
    ) {
        val textWidth = labelPaint.measureText(text)
        val tagTop = max(geometry.priceTop, y - dp(8f))
        val tagBottom = min(geometry.priceBottom, y + dp(8f))
        labelPaint.color = textColor
        canvas.drawRect(
            geometry.right + dp(2f),
            tagTop,
            min(width.toFloat(), geometry.right + textWidth + dp(8f)),
            tagBottom,
            background
        )
        canvas.drawText(
            text,
            geometry.right + dp(4f),
            (tagTop + tagBottom) / 2f + dp(3f),
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
        val key = timeframeKey.trim().lowercase(Locale.US)

        val timeframeIsIntraday =
            key.endsWith("m") ||
            key.endsWith("min") ||
            key.endsWith("h") ||
            key.contains("intraday")

        val intraday =
            smallestGap < 86_400_000L ||
                (smallestGap == Long.MAX_VALUE && timeframeIsIntraday)
        val formatter = when {
            !intraday -> axisTimeFormat
            PriceScaleEngine.needsDateLabel(
                candles[geometry.start].timestamp,
                candles[geometry.end - 1].timestamp
            ) -> axisIntradayWithDateFormat
            else -> axisIntradayFormat
        }

        val indices = timeGridIndices(geometry)
        val lastPosition = indices.lastIndex
        indices.forEachIndexed { position, index ->
            val candle = candles.getOrNull(index) ?: return@forEachIndexed
            if (candle.timestamp <= 0L) return@forEachIndexed
            val x = xFor(index, geometry).coerceIn(geometry.left, geometry.right)
            val text = formatter.format(Date(candle.timestamp))
            axisPaint.textAlign = when (position) {
                0 -> Paint.Align.LEFT
                lastPosition -> Paint.Align.RIGHT
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

        val index = crosshairIndex.coerceIn(
            geometry.start,
            geometry.end - 1
        )

        // Original market candle for accurate OHLCV.
        // Display candles remain responsible for chart rendering.
        val candle = source.getOrNull(index)
            ?: candles.getOrNull(index)
            ?: return

        val x = xFor(index, geometry)
        val y = crosshairY.coerceIn(
            geometry.priceTop,
            geometry.priceBottom
        )

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
                max(
                    geometry.left,
                    geometry.right - timeWidth - dp(10f)
                )
            ),
            height - dp(18f),
            (x + timeWidth / 2f + dp(5f)).coerceIn(
                min(
                    geometry.right,
                    geometry.left + timeWidth + dp(10f)
                ),
                width.toFloat()
            ),
            height.toFloat(),
            crosshairTagPaint
        )
        canvas.drawText(
            timeText,
            (x - timeWidth / 2f).coerceIn(
                geometry.left + dp(2f),
                max(
                    geometry.left + dp(2f),
                    geometry.right - timeWidth
                )
            ),
            height - dp(5f),
            tooltipTextPaint
        )

        val info = "O ${formatAbsolutePrice(candle.open)} " +
            "H ${formatAbsolutePrice(candle.high)} " +
            "L ${formatAbsolutePrice(candle.low)} " +
            "C ${formatAbsolutePrice(candle.close)} " +
            "V ${formatQuantity(candle.volume)}"
        val infoWidth = min(
            geometry.right - geometry.left - dp(8f),
            tooltipTextPaint.measureText(info) + dp(12f)
        )
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
        if (candles.isEmpty()) return super.onTouchEvent(event)

        doubleTapDetector.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            obtainVelocityTracker(event)
        } else {
            velocityTracker?.addMovement(event)
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN && suppressNextAxisDown) {
            suppressNextAxisDown = false
            recycleVelocityTracker()
            cancelAnimations()
            return true
        }

        if (isAxisZooming && event.pointerCount > 1) {
            finishAxisZoom(0f, cancelled = true)
        }

        if (isAxisZooming) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    axisLastY = event.y
                    updateAxisZoom(event.y)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    val velocityY = consumeVelocity().second
                    removeCallbacks(crosshairHoldRunnable)
                    isTouchDown = false
                    crosshairHoldActivated = false
                    isPricePanning = false
                    crosshairIndex = -1
                    finishAxisZoom(velocityY, cancelled = false)
                    crosshairVisible = false
                    onCrosshairChanged?.invoke(null, -1)
                    invalidate()
                    performClick()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(crosshairHoldRunnable)
                    isTouchDown = false
                    crosshairHoldActivated = false
                    isPricePanning = false
                    crosshairIndex = -1
                    finishAxisZoom(0f, cancelled = true)
                    crosshairVisible = false
                    onCrosshairChanged?.invoke(null, -1)
                    invalidate()
                    return true
                }
            }
        }

        scaleDetector.onTouchEvent(event)

        if (scaleDetector.isInProgress) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                recycleVelocityTracker()
            }
            removeCallbacks(crosshairHoldRunnable)
            isTouchDown = false
            crosshairHoldActivated = false
            isPanning = false
            isPricePanning = false
            lastTouchX = event.x
            lastTouchY = event.y
            touchStartX = event.x
            touchStartY = event.y
            horizontalOverscroll = 0f
            crosshairVisible = false
            onCrosshairChanged?.invoke(null, -1)
            invalidate()
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelAnimations()
                lastTouchX = event.x
                lastTouchY = event.y
                touchStartX = event.x
                touchStartY = event.y
                    isPanning = false
                    isPricePanning = false
                    drawingStartValid = false
                    panRawOffset = scrollOffset
                    horizontalOverscroll = 0f

                    previewX = event.x
                    previewY = event.y

                    val geometry = computeGeometry()
                    if (geometry != null &&
                        isInsidePricePlotArea(event.x, event.y, geometry)
                    ) {
                        drawingStartValid = true
                    }
                    if (geometry != null &&
                        isInsidePriceAxis(event.x, event.y, geometry)
                    ) {
                    startAxisZoom(event.y, geometry)
                    invalidate()
                    return true
                }

                isTouchDown = true
                crosshairHoldActivated = false
                crosshairVisible = false
                crosshairIndex = -1

                postDelayed(crosshairHoldRunnable, 3_000L)

                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                previewX = event.x
                previewY = event.y

                val movedX = abs(event.x - touchStartX)
                val movedY = abs(event.y - touchStartY)

                if (!crosshairHoldActivated &&
                    (movedX > touchSlop || movedY > touchSlop)
                ) {
                    removeCallbacks(crosshairHoldRunnable)
                }

                if (crosshairHoldActivated) {
                    val geometry = computeGeometry()

                    if (geometry != null) {
                        crosshairX = event.x.coerceIn(
                            geometry.left,
                            geometry.right
                        )

                        crosshairY = event.y.coerceIn(
                            geometry.priceTop,
                            geometry.priceBottom
                        )

                        crosshairIndex = indexAt(crosshairX, geometry)

                        onCrosshairChanged?.invoke(
                            source.getOrNull(crosshairIndex),
                            crosshairIndex
                        )
                    }

                    invalidate()
                    return true
                }

                if (!isPanning &&
                    (movedX > touchSlop || movedY > touchSlop)
                ) {
                    removeCallbacks(crosshairHoldRunnable)
                    isPanning = true
                    panRawOffset = scrollOffset
                    lastTouchX = event.x

                    crosshairVisible = false
                    crosshairIndex = -1
                    onCrosshairChanged?.invoke(null, -1)
                }

                if (isPanning) {
                    val deltaX = event.x - lastTouchX
                    val geometry = lastGeometry ?: computeGeometry()

                    if (geometry != null && geometry.slot > 0f) {
                        panRawOffset -= deltaX / geometry.slot
                        applyPanOffset(geometry)
                    }
                }

                lastTouchX = event.x
                lastTouchY = event.y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val velocityX = consumeVelocity().first
                val wasCrosshair = crosshairHoldActivated

                removeCallbacks(crosshairHoldRunnable)
                isTouchDown = false
                crosshairHoldActivated = false
                crosshairIndex = -1
                crosshairVisible = false
                onCrosshairChanged?.invoke(null, -1)

                val releaseGeometry = lastGeometry ?: computeGeometry()
                if (isPanning) {
                    finishPan(velocityX, releaseGeometry)
                } else if (!isPricePanning &&
                    !wasCrosshair &&
                    activeTool != ChartTool.CROSSHAIR &&
                    drawingStartValid &&
                    releaseGeometry != null &&
                    isInsidePricePlotArea(event.x, event.y, releaseGeometry)
                ) {
                    handleDrawingTap(event.x, event.y)
                }

                isPanning = false
                isPricePanning = false
                previewX = 0f
                previewY = 0f

                invalidate()
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                recycleVelocityTracker()

                removeCallbacks(crosshairHoldRunnable)
                isTouchDown = false
                crosshairHoldActivated = false
                drawingStartValid = false
                crosshairIndex = -1
                crosshairVisible = false
                onCrosshairChanged?.invoke(null, -1)

                if (isPanning) {
                    finishPan(0f, lastGeometry ?: computeGeometry())
                }

                isPanning = false
                isPricePanning = false
                previewX = 0f
                previewY = 0f

                invalidate()
                return true
            }
        }

        return true
    }

    private fun startAxisZoom(y: Float, geometry: Geometry) {
        cancelAnimations()

        val autoRange = computeAutoPriceRange(geometry.start, geometry.end)
        val currentRange = manualPriceRange?.takeIf { it.isValid() } ?: autoRange
        val height = max(1f, geometry.priceBottom - geometry.priceTop)
        val anchorRatio = ((geometry.priceBottom - y) / height).coerceIn(0f, 1f)

        isAxisZooming = true
        isPricePanning = true
        axisStartY = y
        axisLastY = y
        axisStartRange = currentRange
        axisAnchorPrice = priceAt(y, geometry)
        axisAnchorRatio = anchorRatio.toDouble()
        axisMinSpan = PriceScaleEngine.minZoomSpan(autoRange)
        axisMaxSpan = PriceScaleEngine.maxZoomSpan(autoRange)

        crosshairVisible = false
        crosshairIndex = -1
        onCrosshairChanged?.invoke(null, -1)
    }

    private fun updateAxisZoom(y: Float) {
        val geometry = lastGeometry ?: computeGeometry() ?: return
        manualPriceRange = PriceScaleEngine.axisZoomRange(
            startRange = axisStartRange,
            travel = PriceScaleEngine.axisZoomTravel(axisStartY, y, 0f),
            height = geometry.priceBottom - geometry.priceTop,
            anchorPrice = axisAnchorPrice,
            anchorRatio = axisAnchorRatio,
            minSpan = axisMinSpan,
            maxSpan = axisMaxSpan,
            sensitivity = AXIS_ZOOM_SENSITIVITY,
            elastic = true
        )
        invalidate()
    }

    private fun finishAxisZoom(velocityY: Float, cancelled: Boolean) {
        if (!isAxisZooming) return
        isAxisZooming = false
        isPricePanning = false

        val geometry = lastGeometry ?: computeGeometry()
        val currentRange = manualPriceRange ?: axisStartRange
        if (geometry == null) {
            manualPriceRange = PriceScaleEngine.sanitizeRange(
                currentRange.min,
                currentRange.max
            )
            return
        }

        val height = geometry.priceBottom - geometry.priceTop
        val travel = if (cancelled) {
            PriceScaleEngine.axisZoomTravel(axisStartY, axisLastY, 0f)
        } else {
            PriceScaleEngine.axisZoomTravel(axisStartY, axisLastY, velocityY, AXIS_VELOCITY_PROJECTION)
        }
        val targetRange = PriceScaleEngine.axisZoomRange(
            startRange = axisStartRange,
            travel = travel,
            height = height,
            anchorPrice = axisAnchorPrice,
            anchorRatio = axisAnchorRatio,
            minSpan = axisMinSpan,
            maxSpan = axisMaxSpan,
            sensitivity = AXIS_ZOOM_SENSITIVITY,
            elastic = false
        )

        if (manualPriceRange == null &&
            abs(targetRange.min - axisStartRange.min) < 1.0e-9 &&
            abs(targetRange.max - axisStartRange.max) < 1.0e-9
        ) {
            invalidate()
            return
        }

        animatePriceRange(currentRange, targetRange)
    }

    private fun animatePriceRange(from: PriceRange, target: PriceRange) {
        priceAnimator?.cancel()
        val start = if (from.isValid()) from else target
        if (!target.isValid()) return
        if (abs(start.min - target.min) < 1.0e-9 &&
            abs(start.max - target.max) < 1.0e-9
        ) {
            manualPriceRange = target
            invalidate()
            return
        }

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PRICE_ANIMATION_MS
            interpolator = DecelerateInterpolator(1.35f)
            addUpdateListener { valueAnimator ->
                val fraction = valueAnimator.animatedValue as Float
                manualPriceRange = PriceRange(
                    start.min + (target.min - start.min) * fraction,
                    start.max + (target.max - start.max) * fraction
                )
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    manualPriceRange = target
                    priceAnimator = null
                    invalidate()
                }
            })
        }
        priceAnimator = animator
        animator.start()
    }

    private fun applyPanOffset(geometry: Geometry) {
        val maxScroll = PriceScaleEngine.maxScroll(candles.size, visibleCount)
        val result = PriceScaleEngine.panOffset(
            rawOffset = panRawOffset,
            slot = geometry.slot,
            maxScroll = maxScroll,
            plotWidth = geometry.right - geometry.left,
            maxOverscrollPx = dp(48f)
        )

        scrollOffset = result.scrollOffset
        horizontalOverscroll = result.overscroll
        followLatest = result.atEnd
    }

    private fun finishPan(velocityX: Float, geometry: Geometry?) {
        if (geometry == null || geometry.slot <= 0f) {
            horizontalOverscroll = 0f
            return
        }

        val maxScroll = PriceScaleEngine.maxScroll(candles.size, visibleCount)
        val effectiveVelocity = if (abs(velocityX) >= minFlingVelocity) velocityX else 0f
        val target = PriceScaleEngine.flingTargetOffset(
            currentOffset = scrollOffset,
            velocityX = effectiveVelocity,
            slot = geometry.slot,
            maxScroll = maxScroll,
            projection = PAN_VELOCITY_PROJECTION
        )
        val startScroll = scrollOffset
        val startOverscroll = horizontalOverscroll

        if (abs(target - startScroll) < 0.01f && abs(startOverscroll) < 0.5f) {
            scrollOffset = target
            horizontalOverscroll = 0f
            followLatest = target >= maxScroll - 0.01f
            invalidate()
            return
        }

        scrollAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PAN_ANIMATION_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { valueAnimator ->
                val fraction = valueAnimator.animatedValue as Float
                scrollOffset = startScroll + (target - startScroll) * fraction
                horizontalOverscroll = startOverscroll * (1f - fraction)
                followLatest = scrollOffset >= maxScroll - 0.01f
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    scrollOffset = target
                    horizontalOverscroll = 0f
                    followLatest = target >= maxScroll - 0.01f
                    scrollAnimator = null
                    invalidate()
                }
            })
        }
        scrollAnimator = animator
        animator.start()
    }

    private fun obtainVelocityTracker(event: MotionEvent) {
        recycleVelocityTracker()
        velocityTracker = VelocityTracker.obtain().apply {
            addMovement(event)
        }
    }

    private fun consumeVelocity(): Pair<Float, Float> {
        val tracker = velocityTracker ?: return 0f to 0f
        tracker.computeCurrentVelocity(
            1000,
            dp(MAX_FLING_VELOCITY_DP)
        )
        val velocity = tracker.xVelocity to tracker.yVelocity
        tracker.recycle()
        velocityTracker = null
        return velocity
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun cancelAnimations() {
        scrollAnimator?.cancel()
        scrollAnimator = null
        priceAnimator?.cancel()
        priceAnimator = null
        horizontalOverscroll = 0f
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(crosshairHoldRunnable)
        isTouchDown = false
        crosshairHoldActivated = false
        isPanning = false
        isPricePanning = false
        drawingStartValid = false
        lastPricePulseAnimator?.cancel()
        lastPricePulseAnimator = null
        cancelAnimations()
        recycleVelocityTracker()
        super.onDetachedFromWindow()
    }

    private fun handleDrawingTap(x: Float, y: Float) {
        val geometry = computeGeometry() ?: return
        if (!isInsidePricePlotArea(x, y, geometry)) return
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

    private fun isUsableRelativeReference(value: Double): Boolean {
        return value.isFinite() && abs(value) > RELATIVE_REFERENCE_EPS
    }

    private fun updateRelativeReference(geometry: Geometry) {
        if (priceScaleMode != PriceScaleMode.RELATIVE) {
            relativeReference = Double.NaN
            return
        }
        var reference = Double.NaN
        for (index in geometry.start until geometry.end) {
            val close = candles.getOrNull(index)?.close ?: continue
            if (isUsableRelativeReference(close)) {
                reference = close
                break
            }
        }
        relativeReference = reference
    }

    private fun previousCloseForLastPrice(): Double? {
        return previousClose?.takeIf { PriceScaleEngine.isUsablePrice(it) }
            ?: source.getOrNull(source.lastIndex - 1)?.close
                ?.takeIf { PriceScaleEngine.isUsablePrice(it) }
            ?: source.lastOrNull()?.close
                ?.takeIf { PriceScaleEngine.isUsablePrice(it) }
    }

    private fun formatPrice(value: Double): String {
        if (!value.isFinite()) return "—"
        if (priceScaleMode == PriceScaleMode.RELATIVE) {
            val reference = relativeReference.takeIf { isUsableRelativeReference(it) }
                ?: return "—"
            val percent = (value - reference) / abs(reference) * 100.0
            val sign = if (percent > 0.0) "+" else ""
            return String.format(Locale.US, "%s%.2f%%", sign, percent)
        }
        return formatAbsolutePrice(value)
    }

    private fun formatAbsolutePrice(value: Double): String {
        return PriceScaleEngine.formatPrice(value, priceDecimals)
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
        val range: PriceScaleEngine.CandleRange,
        val startFloat: Float,
        val visibleFloat: Float,
        val minPrice: Double,
        val maxPrice: Double,
        val subCharts: List<SubChartArea>
    )

    private companion object {
        const val MIN_VISIBLE = 6f
        const val MIN_PANEL_HEIGHT_DP = 24f
        const val MIN_MAIN_HEIGHT_DP = 60f
        const val MIN_VOLUME_HEIGHT_DP = 12f
        const val RELATIVE_REFERENCE_EPS = 1.0e-6
        const val AXIS_ZOOM_SENSITIVITY = PriceScaleEngine.AXIS_ZOOM_SENSITIVITY
        const val AXIS_VELOCITY_PROJECTION = PriceScaleEngine.AXIS_VELOCITY_PROJECTION
        const val PAN_VELOCITY_PROJECTION = PriceScaleEngine.PAN_VELOCITY_PROJECTION
        const val MAX_FLING_VELOCITY_DP = 8000f
        const val PRICE_ANIMATION_MS = 280L
        const val PAN_ANIMATION_MS = 260L
    }
}
