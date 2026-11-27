package com.sb.ui.chart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ProfessionalChartViewTest {

    @Test
    fun panDragMovesCandlesOneToOneWithoutChangingPriceRange() {
        val view = createView()
        val geometry = geometry(view)
        val slot = geometry.float("slot")
        val left = geometry.float("left")
        val right = geometry.float("right")
        val top = geometry.float("priceTop")
        val bottom = geometry.float("priceBottom")
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
        val triggerX = centerX + slop + 1f
        val initialOffset = view.float("scrollOffset")

        val events = Events()
        assertTrue(view.dispatchTouchEvent(events.down(centerX, centerY)))
        assertTrue(view.dispatchTouchEvent(events.move(triggerX, centerY)))
        assertEquals(initialOffset, view.float("scrollOffset"), 0.0001f)
        assertTrue(view.boolean("isPanning"))

        assertTrue(view.dispatchTouchEvent(events.move(triggerX + 100f, centerY)))
        assertEquals(
            initialOffset - 100f / slot,
            view.float("scrollOffset"),
            0.05f
        )
        assertEquals(0f, view.float("horizontalOverscroll"), 0.0001f)
        assertNull(view.any("manualPriceRange"))

        view.dispatchTouchEvent(events.cancel(triggerX + 100f, centerY))
        assertNull(view.any("velocityTracker"))
        assertFalse(view.boolean("isPanning"))
        assertFalse(view.boolean("drawingStartValid"))
    }

    @Test
    fun verticalDragPansPriceRangeWithoutChangingSpan() {
        val view = createView()
        val geometry = geometry(view)
        val left = geometry.float("left")
        val right = geometry.float("right")
        val top = geometry.float("priceTop")
        val bottom = geometry.float("priceBottom")
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        val startMin = geometry.double("minPrice")
        val startSpan = geometry.double("maxPrice") - startMin
        val initialOffset = view.float("scrollOffset")
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
        val triggerY = centerY - slop - 1f
        val dragDistance = 120f

        val events = Events()
        view.dispatchTouchEvent(events.down(centerX, centerY))
        view.dispatchTouchEvent(events.move(centerX, triggerY))
        view.dispatchTouchEvent(events.move(centerX, triggerY - dragDistance))
        view.dispatchTouchEvent(events.cancel(centerX, triggerY - dragDistance))

        render(view)
        val after = geometry(view)
        val manual = view.any("manualPriceRange") as? PriceRange
        assertNotNull(manual)
        assertEquals(initialOffset, view.float("scrollOffset"), 0.0001f)
        assertEquals(0f, view.float("horizontalOverscroll"), 0.0001f)
        assertEquals(
            startSpan,
            after.double("maxPrice") - after.double("minPrice"),
            startSpan * 0.001
        )
        assertEquals(
            startMin - dragDistance / (bottom - top) * startSpan,
            after.double("minPrice"),
            startSpan * 0.01
        )
    }

    @Test
    fun longPressActivatesCrosshairAndBlocksPanningUntilRelease() {
        val view = createView()
        val geometry = geometry(view)
        val centerX = (geometry.float("left") + geometry.float("right")) / 2f
        val centerY = (geometry.float("priceTop") + geometry.float("priceBottom")) / 2f
        val initialOffset = view.float("scrollOffset")

        val events = Events()
        view.dispatchTouchEvent(events.down(centerX, centerY))
        assertFalse(view.boolean("crosshairVisible"))
        assertTrue(view.boolean("isTouchDown"))

        (view.any("crosshairHoldRunnable") as Runnable).run()
        assertTrue(view.boolean("crosshairVisible"))
        assertFalse(view.boolean("isPanning"))
        assertFalse(view.boolean("isPricePanning"))
        assertTrue(view.int("crosshairIndex") >= 0)

        view.dispatchTouchEvent(events.move(centerX + 80f, centerY - 40f))
        assertEquals(initialOffset, view.float("scrollOffset"), 0.0001f)
        assertNull(view.any("manualPriceRange"))
        assertTrue(view.boolean("crosshairVisible"))

        view.dispatchTouchEvent(events.up(centerX + 80f, centerY - 40f))
        assertFalse(view.boolean("crosshairVisible"))
        assertEquals(-1, view.int("crosshairIndex"))
    }

    @Test
    fun livePriceOutsideManualRangeShiftsRangeWithoutChangingSpan() {
        val view = createView()
        val geometry = geometry(view)
        val min = geometry.double("minPrice")
        val max = geometry.double("maxPrice")
        val span = max - min
        setField(view, "manualPriceRange", PriceRange(min, max))

        view.lastPrice = max + span * 0.1

        val shifted = view.any("manualPriceRange") as PriceRange
        assertEquals(span, shifted.max - shifted.min, span * 0.001)
        assertTrue(shifted.max >= view.double("lastPrice"))
        assertTrue(shifted.min > min)
    }

    @Test
    fun axisDragDownZoomsInAndUpZoomsOutWhileKeepingAnchor() {
        val view = createView()
        val density = view.context.resources.displayMetrics.density
        val geometry = geometry(view)
        val axisX = geometry.float("right") + 12f * density
        val y = (geometry.float("priceTop") + geometry.float("priceBottom")) / 2f
        val anchor = PriceScaleEngine.priceForY(
            y,
            PriceRange(geometry.double("minPrice"), geometry.double("maxPrice")),
            geometry.float("priceTop"),
            geometry.float("priceBottom")
        )
        val startSpan = geometry.double("maxPrice") - geometry.double("minPrice")
        val initialOffset = view.float("scrollOffset")

        val zoomInEvents = Events()
        view.dispatchTouchEvent(zoomInEvents.down(axisX, y))
        assertTrue(view.boolean("isAxisZooming"))
        view.dispatchTouchEvent(zoomInEvents.move(axisX, y + 48f * density))
        view.dispatchTouchEvent(zoomInEvents.cancel(axisX, y + 48f * density))

        render(view)
        val zoomedIn = geometry(view)
        val zoomedInSpan = zoomedIn.double("maxPrice") - zoomedIn.double("minPrice")
        assertTrue(zoomedInSpan < startSpan)
        assertEquals(initialOffset, view.float("scrollOffset"), 0.0001f)
        assertEquals(
            anchor,
            PriceScaleEngine.priceForY(
                y,
                PriceRange(zoomedIn.double("minPrice"), zoomedIn.double("maxPrice")),
                zoomedIn.float("priceTop"),
                zoomedIn.float("priceBottom")
            ),
            startSpan * 0.01
        )

        val anchorAfterZoomIn = PriceScaleEngine.priceForY(
            y,
            PriceRange(zoomedIn.double("minPrice"), zoomedIn.double("maxPrice")),
            zoomedIn.float("priceTop"),
            zoomedIn.float("priceBottom")
        )
        val zoomOutEvents = Events()
        view.dispatchTouchEvent(zoomOutEvents.down(axisX, y))
        view.dispatchTouchEvent(zoomOutEvents.move(axisX, y - 48f * density))
        view.dispatchTouchEvent(zoomOutEvents.cancel(axisX, y - 48f * density))

        render(view)
        val zoomedOut = geometry(view)
        val zoomedOutSpan = zoomedOut.double("maxPrice") - zoomedOut.double("minPrice")
        assertTrue(zoomedOutSpan > zoomedInSpan)
        assertEquals(initialOffset, view.float("scrollOffset"), 0.0001f)
        assertEquals(
            anchorAfterZoomIn,
            PriceScaleEngine.priceForY(
                y,
                PriceRange(zoomedOut.double("minPrice"), zoomedOut.double("maxPrice")),
                zoomedOut.float("priceTop"),
                zoomedOut.float("priceBottom")
            ),
            startSpan * 0.01
        )
        assertNull(view.any("velocityTracker"))
    }

    @Test
    fun axisZoomDoesNotStartOutsidePriceAxis() {
        val view = createView()
        val density = view.context.resources.displayMetrics.density
        val geometry = geometry(view)
        val plotX = geometry.float("right") - 8f * density
        val axisX = geometry.float("right") + 12f * density
        val priceY = (geometry.float("priceTop") + geometry.float("priceBottom")) / 2f
        val volumeY = (geometry.float("volumeTop") + geometry.float("volumeBottom")) / 2f

        val plotEvents = Events()
        view.dispatchTouchEvent(plotEvents.down(plotX, priceY))
        assertFalse(view.boolean("isAxisZooming"))
        view.dispatchTouchEvent(plotEvents.cancel(plotX, priceY))

        val volumeEvents = Events()
        view.dispatchTouchEvent(volumeEvents.down(axisX, volumeY))
        assertFalse(view.boolean("isAxisZooming"))
        view.dispatchTouchEvent(volumeEvents.cancel(axisX, volumeY))
    }

    @Test
    fun drawingTapsAreAcceptedOnlyInsidePricePlot() {
        val view = createView()
        view.activeTool = ChartTool.TRENDLINE
        view.clearDrawings()

        val density = view.context.resources.displayMetrics.density
        val geometry = geometry(view)
        val left = geometry.float("left")
        val right = geometry.float("right")
        val top = geometry.float("priceTop")
        val bottom = geometry.float("priceBottom")
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        val volumeY = (geometry.float("volumeTop") + geometry.float("volumeBottom")) / 2f
        val axisX = right + 12f * density

        tap(view, centerX, centerY)
        tap(view, centerX + 40f * density, centerY + 20f * density)
        assertEquals(1, view.drawingCount())

        val downInsideUpOutside = Events()
        view.dispatchTouchEvent(downInsideUpOutside.down(centerX, centerY))
        view.dispatchTouchEvent(downInsideUpOutside.up(centerX, volumeY))
        assertEquals(1, view.drawingCount())

        val downOutsideUpInside = Events()
        view.dispatchTouchEvent(downOutsideUpInside.down(centerX, volumeY))
        view.dispatchTouchEvent(downOutsideUpInside.up(centerX, centerY))
        assertEquals(1, view.drawingCount())

        tap(view, axisX, centerY)
        assertEquals(1, view.drawingCount())
    }

    @Test
    fun drawingsAreClippedToPricePlot() {
        val view = createView()
        val geometry = geometry(view)
        val baseline = render(view)
        val minPrice = geometry.double("minPrice")
        val maxPrice = geometry.double("maxPrice")
        val span = maxPrice - minPrice
        val density = view.context.resources.displayMetrics.density

        @Suppress("UNCHECKED_CAST")
        val drawings = view.any("drawings") as MutableList<ChartDrawing>
        drawings.add(
            ChartDrawing(
                ChartTool.HORIZONTAL,
                mutableListOf(ChartPoint(geometry.int("start") + 2, minPrice - span))
            )
        )
        drawings.add(
            ChartDrawing(
                ChartTool.TRENDLINE,
                mutableListOf(
                    ChartPoint(-50, minPrice - span),
                    ChartPoint(geometry.int("end") + 50, maxPrice + span)
                )
            )
        )

        val after = render(view)
        val left = geometry.float("left").toInt()
        val right = geometry.float("right").toInt()
        val priceTop = geometry.float("priceTop").toInt()
        val priceBottom = geometry.float("priceBottom").toInt()

        var changedInside = false
        for (x in (left + 8) until right step 4) {
            for (y in (priceTop + 8) until priceBottom step 4) {
                if (baseline.getPixel(x, y) != after.getPixel(x, y)) {
                    changedInside = true
                    break
                }
            }
            if (changedInside) break
        }
        assertTrue(changedInside)

        assertRegionsEqual(
            baseline,
            after,
            (right + (4f * density).toInt()).coerceAtMost(view.width - 1),
            view.width - 1,
            priceTop + 1,
            priceBottom - 1
        )
        assertRegionsEqual(
            baseline,
            after,
            left + 1,
            right - 1,
            geometry.float("volumeTop").toInt() + 1,
            geometry.float("volumeBottom").toInt() - 1
        )
        assertRegionsEqual(
            baseline,
            after,
            0,
            view.width - 1,
            (geometry.float("bottom") + 4f * density).toInt().coerceAtMost(view.height - 1),
            view.height - 1
        )
    }

    @Test
    fun pinchKeepsFocusedCandleStableAndReleasesVelocityTracker() {
        val view = createView()
        val density = view.context.resources.displayMetrics.density
        val geometry = geometry(view)
        val focusX = (geometry.float("left") + geometry.float("right")) / 2f
        val focusY = (geometry.float("priceTop") + geometry.float("priceBottom")) / 2f
        val indexBefore = PriceScaleEngine.indexAt(
            focusX,
            geometry.candleRange(),
            geometry.float("left"),
            0f
        )
        val slotBefore = geometry.float("slot")
        val gapStart = 40f * density
        val gapEnd = 90f * density

        val events = MultiEvents()
        view.dispatchTouchEvent(events.down(focusX - gapStart, focusY))
        view.dispatchTouchEvent(
            events.pointerDown(focusX - gapStart, focusY, focusX + gapStart, focusY)
        )
        view.dispatchTouchEvent(
            events.move(focusX - gapEnd, focusY, focusX + gapEnd, focusY)
        )
        view.dispatchTouchEvent(
            events.pointerUp(focusX - gapEnd, focusY, focusX + gapEnd, focusY)
        )
        view.dispatchTouchEvent(events.up(focusX - gapEnd, focusY))

        render(view)
        val after = geometry(view)
        val indexAfter = PriceScaleEngine.indexAt(
            focusX,
            after.candleRange(),
            after.float("left"),
            view.float("horizontalOverscroll")
        )

        assertTrue(after.float("slot") > slotBefore)
        assertTrue(abs(indexAfter - indexBefore) <= 1)
        assertNull(view.any("velocityTracker"))
        assertFalse(view.boolean("isPanning"))
    }

    @Test
    fun changingToolDuringGestureReleasesTouchResources() {
        val view = createView()
        val geometry = geometry(view)
        val centerX = (geometry.float("left") + geometry.float("right")) / 2f
        val centerY = (geometry.float("priceTop") + geometry.float("priceBottom")) / 2f
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
        val triggerX = centerX - slop - 1f

        val events = Events()
        view.dispatchTouchEvent(events.down(centerX, centerY))
        view.dispatchTouchEvent(events.move(triggerX, centerY))
        view.dispatchTouchEvent(events.move(triggerX - 60f, centerY))
        assertNotNull(view.any("velocityTracker"))
        assertTrue(view.boolean("isPanning"))
        assertTrue(view.boolean("isTouchDown"))

        view.activeTool = ChartTool.HORIZONTAL

        assertNull(view.any("velocityTracker"))
        assertFalse(view.boolean("isPanning"))
        assertFalse(view.boolean("isTouchDown"))
        view.dispatchTouchEvent(events.cancel(triggerX - 60f, centerY))
    }

    @Test
    fun subchartAndVolumePaintsAreClippedAtRightEdge() {
        val view = createView()
        view.indicators = setOf(ChartIndicator.RSI_14, ChartIndicator.MACD)
        render(view)
        val geometry = geometry(view)
        val count = (view.any("source") as List<*>).size

        setIndicatorResult(view) { result ->
            result.copy(
                rsi14 = DoubleArray(count) { 800.0 },
                macd = IndicatorMath.MacdResult(
                    macd = DoubleArray(count) { 1.0e6 },
                    signal = DoubleArray(count) { -1.0e6 },
                    histogram = DoubleArray(count) {
                        if (it % 2 == 0) 1.0e6 else -1.0e6
                    }
                )
            )
        }
        val baseline = render(view)

        setField(view, "horizontalOverscroll", 2400f)
        val after = render(view)

        val density = view.context.resources.displayMetrics.density
        assertRegionsEqual(
            baseline,
            after,
            (geometry.float("right") + 4f * density).toInt().coerceAtMost(view.width - 1),
            view.width - 1,
            geometry.float("top").toInt() + 1,
            geometry.float("bottom").toInt() - 1
        )
    }

    @Test
    fun appendingCandlesRemapsDrawingsAndCrosshairByTimestamp() {
        val view = createView()
        view.activeTool = ChartTool.HORIZONTAL
        view.clearDrawings()
        val geometry = geometry(view)
        val centerX = (geometry.float("left") + geometry.float("right")) / 2f
        val centerY = (geometry.float("priceTop") + geometry.float("priceBottom")) / 2f
        tap(view, centerX, centerY)

        @Suppress("UNCHECKED_CAST")
        val beforeDrawings = view.any("drawings") as List<ChartDrawing>
        val beforeIndex = beforeDrawings.single().points.single().index
        setField(view, "crosshairIndex", beforeIndex)
        setField(view, "crosshairVisible", true)

        val initial = candles(100)
        val appended = initial + initial.last().copy(
            timestamp = initial.last().timestamp + 60_000L
        )
        view.setChartData(appended, ChartStyle.CANDLE, preserveViewport = true)
        render(view)

        @Suppress("UNCHECKED_CAST")
        val remapped = view.any("drawings") as List<ChartDrawing>
        assertEquals(1, remapped.size)
        assertEquals(beforeIndex, remapped.single().points.single().index)
        assertEquals(beforeIndex, view.int("crosshairIndex"))
        assertTrue(view.boolean("crosshairVisible"))

        val shifted = appended.filterIndexed { index, _ -> index != beforeIndex }
        view.setChartData(shifted, ChartStyle.CANDLE, preserveViewport = true)
        render(view)

        assertTrue((view.any("drawings") as List<*>).isEmpty())
        assertEquals(-1, view.int("crosshairIndex"))
        assertFalse(view.boolean("crosshairVisible"))
    }

    @Test
    fun singleCandleUsesTimeframeKeyForIntradayAxis() {
        val candle = candles(1).first()

        fun renderSingle(key: String): Pair<Bitmap, ProfessionalChartView> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val density = context.resources.displayMetrics.density
            val view = ProfessionalChartView(context)
            view.layout(0, 0, (380 * density).toInt(), (640 * density).toInt())
            view.timeframeKey = key
            view.setChartData(listOf(candle), ChartStyle.CANDLE)
            return render(view) to view
        }

        val (intradayBitmap, intradayView) = renderSingle("1m")
        val (dailyBitmap, _) = renderSingle("1D")
        val bottom = geometry(intradayView).float("bottom").toInt() + 2

        assertRegionsDiffer(
            intradayBitmap,
            dailyBitmap,
            0,
            intradayBitmap.width - 1,
            bottom,
            intradayBitmap.height - 1
        )
    }

    @Test
    fun axisZoomSetsAndClearsPricePanningFlag() {
        val view = createView()
        val density = view.context.resources.displayMetrics.density
        val geometry = geometry(view)
        val axisX = geometry.float("right") + 12f * density
        val y = (geometry.float("priceTop") + geometry.float("priceBottom")) / 2f
        val events = Events()

        view.dispatchTouchEvent(events.down(axisX, y))
        assertTrue(view.boolean("isAxisZooming"))
        assertTrue(view.boolean("isPricePanning"))

        view.dispatchTouchEvent(events.move(axisX, y + 40f * density))
        assertTrue(view.boolean("isPricePanning"))

        view.dispatchTouchEvent(events.cancel(axisX, y + 40f * density))
        assertFalse(view.boolean("isAxisZooming"))
        assertFalse(view.boolean("isPricePanning"))
    }

    private fun setIndicatorResult(
        view: ProfessionalChartView,
        transform: (IndicatorIncrementalCache.Result) -> IndicatorIncrementalCache.Result
    ) {
        val cache = view.any("indicatorCache") as IndicatorIncrementalCache
        setField(cache, "candles", view.any("source"))
        setField(cache, "indicatorKey", view.any("indicators"))
        setField(cache, "timeframeKey", view.any("timeframeKey"))
        setField(cache, "result", transform(cache.lastResult))
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun assertRegionsDiffer(
        expected: Bitmap,
        actual: Bitmap,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int
    ) {
        if (x0 >= x1 || y0 >= y1) {
            fail("Invalid comparison region")
        }
        for (x in x0..x1) {
            for (y in y0..y1) {
                if (expected.getPixel(x, y) != actual.getPixel(x, y)) return
            }
        }
        fail("Expected the compared region to differ")
    }

    private fun createView(widthDp: Int = 380, heightDp: Int = 640): ProfessionalChartView {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        val view = ProfessionalChartView(context)
        view.layout(
            0,
            0,
            (widthDp * density).toInt(),
            (heightDp * density).toInt()
        )
        view.setChartData(candles(100), ChartStyle.CANDLE)
        render(view)
        return view
    }

    private fun candles(count: Int): List<ChartCandle> = (0 until count).map { index ->
        val base = 100.0 + (index % 20) * 0.5
        ChartCandle(
            timestamp = 1_700_000_000_000L + index * 60_000L,
            open = base,
            high = base + 1.5,
            low = base - 1.5,
            close = base + 0.5,
            volume = 1_000L + index
        )
    }

    private fun render(view: ProfessionalChartView): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun geometry(view: ProfessionalChartView): Any =
        view.any("lastGeometry") ?: error("Missing chart geometry")

    private fun tap(view: ProfessionalChartView, x: Float, y: Float) {
        val events = Events()
        view.dispatchTouchEvent(events.down(x, y))
        view.dispatchTouchEvent(events.up(x, y))
    }

    private fun assertRegionsEqual(
        expected: Bitmap,
        actual: Bitmap,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int
    ) {
        if (x0 >= x1 || y0 >= y1) return
        for (x in x0..x1) {
            for (y in y0..y1) {
                val expectedPixel = expected.getPixel(x, y)
                val actualPixel = actual.getPixel(x, y)
                if (expectedPixel != actualPixel) {
                    assertEquals(
                        "Pixel ($x, $y) changed outside the price plot",
                        expectedPixel,
                        actualPixel
                    )
                }
            }
        }
    }

    private fun Any.any(name: String): Any? {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(this)
    }

    private fun Any.float(name: String): Float {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.getFloat(this)
    }

    private fun Any.double(name: String): Double {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.getDouble(this)
    }

    private fun Any.int(name: String): Int {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.getInt(this)
    }

    private fun Any.boolean(name: String): Boolean {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.getBoolean(this)
    }

    private fun Any.candleRange(): PriceScaleEngine.CandleRange =
        any("range") as PriceScaleEngine.CandleRange

    private class Events {
        private val downTime = SystemClock.uptimeMillis()
        private var lastTime = downTime

        private fun nextTime(): Long {
            lastTime = maxOf(lastTime + 16L, SystemClock.uptimeMillis())
            return lastTime
        }

        fun down(x: Float, y: Float): MotionEvent =
            MotionEvent.obtain(downTime, nextTime(), MotionEvent.ACTION_DOWN, x, y, 0)

        fun move(x: Float, y: Float): MotionEvent =
            MotionEvent.obtain(downTime, nextTime(), MotionEvent.ACTION_MOVE, x, y, 0)

        fun up(x: Float, y: Float): MotionEvent =
            MotionEvent.obtain(downTime, nextTime(), MotionEvent.ACTION_UP, x, y, 0)

        fun cancel(x: Float, y: Float): MotionEvent =
            MotionEvent.obtain(downTime, nextTime(), MotionEvent.ACTION_CANCEL, x, y, 0)
    }

    private class MultiEvents {
        private val downTime = SystemClock.uptimeMillis()
        private var lastTime = downTime

        private fun nextTime(): Long {
            lastTime = maxOf(lastTime + 16L, SystemClock.uptimeMillis())
            return lastTime
        }

        private fun properties(): Array<MotionEvent.PointerProperties> = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
            MotionEvent.PointerProperties().apply {
                id = 1
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )

        private fun coordinates(
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float
        ): Array<MotionEvent.PointerCoords> = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = x0
                y = y0
                pressure = 1f
                size = 1f
            },
            MotionEvent.PointerCoords().apply {
                x = x1
                y = y1
                pressure = 1f
                size = 1f
            }
        )

        private fun multi(
            action: Int,
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float
        ): MotionEvent = MotionEvent.obtain(
            downTime,
            nextTime(),
            action,
            2,
            properties(),
            coordinates(x0, y0, x1, y1),
            0,
            0,
            0f,
            0f,
            0,
            0,
            0,
            0
        )

        fun down(x: Float, y: Float): MotionEvent =
            MotionEvent.obtain(downTime, nextTime(), MotionEvent.ACTION_DOWN, x, y, 0)

        fun pointerDown(x0: Float, y0: Float, x1: Float, y1: Float): MotionEvent =
            multi(MotionEvent.ACTION_POINTER_DOWN, x0, y0, x1, y1)

        fun move(x0: Float, y0: Float, x1: Float, y1: Float): MotionEvent =
            multi(MotionEvent.ACTION_MOVE, x0, y0, x1, y1)

        fun pointerUp(x0: Float, y0: Float, x1: Float, y1: Float): MotionEvent =
            multi(
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                x0,
                y0,
                x1,
                y1
            )

        fun up(x: Float, y: Float): MotionEvent =
            MotionEvent.obtain(downTime, nextTime(), MotionEvent.ACTION_UP, x, y, 0)
    }
}
