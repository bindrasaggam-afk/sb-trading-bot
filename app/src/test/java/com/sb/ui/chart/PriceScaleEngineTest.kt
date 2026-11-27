package com.sb.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PriceScaleEngineTest {

    @Test
    fun autoFitRangeKeepsCommodityScaleAndPadding() {
        val range = PriceScaleEngine.autoFitRange(318.0, 340.0)

        assertTrue(range.isValid())
        assertTrue(range.min < 318.0)
        assertTrue(range.max > 340.0)
        assertEquals(22.0 * 1.16, range.span, 0.0001)
        assertTrue(range.min > 0.0)
    }

    @Test
    fun autoFitRangeSupportsNegativePricesWithoutForcingZero() {
        val range = PriceScaleEngine.autoFitRange(-250.0, -100.0)

        assertTrue(range.isValid())
        assertTrue(range.min < -250.0)
        assertTrue(range.max > -100.0)
        assertTrue(range.max < 0.0)
    }

    @Test
    fun priceMappingRoundTripsForNegativeAndPositiveRanges() {
        val ranges = listOf(
            PriceRange(-1_000.0, 1_000.0),
            PriceRange(-0.01, 0.01),
            PriceRange(-1_000_000.0, 1_000_000.0)
        )

        ranges.forEach { range ->
            val prices = listOf(
                range.min,
                range.min + range.span / 3.0,
                (range.min + range.max) / 2.0,
                range.max - range.span / 3.0,
                range.max
            )
            prices.forEach { price ->
                val y = PriceScaleEngine.priceToY(price, range, 0f, 1000f)
                val restored = PriceScaleEngine.priceForY(y, range, 0f, 1000f)
                assertEquals(price, restored, range.span * 1.0e-6)
            }
        }
    }

    @Test
    fun zoomLimitsReachRequiredRange() {
        val range = PriceScaleEngine.autoFitRange(318.0, 340.0)

        assertTrue(PriceScaleEngine.minZoomSpan(range) <= 0.01)
        assertTrue(PriceScaleEngine.maxZoomSpan(range) >= 1_000_000.0)
    }

    @Test
    fun ticksAcrossZeroIncludeZeroAndKeepSigns() {
        val ticks = PriceScaleEngine.ticks(PriceRange(-0.05, 0.05), 6)

        assertTrue(ticks.ticks.any { it.price == 0.0 })
        assertTrue(ticks.ticks.any { it.price < 0.0 })
        assertTrue(ticks.ticks.any { it.price > 0.0 })
    }

    @Test
    fun priceMappingIsExactAndMonotonic() {
        val range = PriceRange(300.0, 400.0)

        assertEquals(1000f, PriceScaleEngine.priceToY(300.0, range, 0f, 1000f), 0.001f)
        assertEquals(500f, PriceScaleEngine.priceToY(350.0, range, 0f, 1000f), 0.001f)
        assertEquals(0f, PriceScaleEngine.priceToY(400.0, range, 0f, 1000f), 0.001f)
        assertEquals(350.0, PriceScaleEngine.priceForY(500f, range, 0f, 1000f), 0.0001)
    }

    @Test
    fun ticksUseTwoDecimalCommodityLabels() {
        val ticks = PriceScaleEngine.ticks(PriceRange(318.0, 340.0), 12)

        assertEquals(2.0, ticks.step, 0.0001)
        assertEquals(2, ticks.decimals)
        assertTrue(ticks.ticks.any { it.label == "318.00" })
        assertTrue(ticks.ticks.any { it.label == "340.00" })
        ticks.ticks.zipWithNext().forEach { (first, second) ->
            assertEquals(2.0, second.price - first.price, 0.0001)
        }
    }

    @Test
    fun tickBudgetPreventsLabelCollisions() {
        val ticks = PriceScaleEngine.ticks(PriceRange(318.0, 340.0), 5)

        assertTrue(ticks.ticks.size <= 5)
        assertTrue(ticks.ticks.size >= 2)
    }

    @Test
    fun elasticSpanResistsBothZoomLimits() {
        val belowMinimum = PriceScaleEngine.elasticSpan(0.001, 1.0, 50.0)
        val aboveMaximum = PriceScaleEngine.elasticSpan(100.0, 1.0, 50.0)

        assertTrue(belowMinimum > 0.0)
        assertTrue(belowMinimum < 1.0)
        assertTrue(aboveMaximum > 50.0)
        assertTrue(aboveMaximum.isFinite())
    }

    @Test
    fun rangeWithAnchorKeepsCenterPriceStable() {
        val range = PriceScaleEngine.rangeWithAnchor(330.0, 10.0)

        assertEquals(325.0, range.min, 0.0001)
        assertEquals(335.0, range.max, 0.0001)
        assertEquals(330.0, (range.min + range.max) / 2.0, 0.0001)
    }

    @Test
    fun invalidInputsProduceSafeScale() {
        val range = PriceScaleEngine.autoFitRange(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            320.0
        )

        assertTrue(range.isValid())
        assertTrue(320.0 in range.min..range.max)
    }

    @Test
    fun signedRubberBandPreservesDirection() {
        val positive = PriceScaleEngine.rubberBandSigned(30.0, 100.0)
        val negative = PriceScaleEngine.rubberBandSigned(-30.0, 100.0)

        assertTrue(positive > 0.0)
        assertTrue(negative < 0.0)
        assertEquals(positive, -negative, 0.0001)
    }

    @Test
    fun candleRangePreservesFractionalScrollAndRoundTrips() {
        val range = PriceScaleEngine.candleRange(2.5f, 10f, 100, 0f, 200f)

        checkNotNull(range)
        assertEquals(2.5f, range.startFloat, 0.0001f)
        assertEquals(10f, range.visibleFloat, 0.0001f)
        assertEquals(20f, range.slot, 0.0001f)
        assertEquals(2, range.start)
        assertEquals(13, range.end)

        val x = PriceScaleEngine.xFor(5, range, 0f, 0f)
        assertEquals(60f, x, 0.0001f)
        assertEquals(5, PriceScaleEngine.indexAt(x, range, 0f, 0f))
    }

    @Test
    fun anchoredZoomKeepsFocusedCandleAtSameX() {
        val oldRange = PriceScaleEngine.candleRange(0f, 40f, 100, 0f, 400f)
        checkNotNull(oldRange)

        val focusX = 135f
        val anchorIndex = oldRange.startFloat + focusX / oldRange.slot
        val newVisible = 20f
        val newSlot = 400f / newVisible
        val newStart = PriceScaleEngine.anchoredStart(
            anchorIndex,
            focusX,
            0f,
            newSlot,
            100,
            newVisible
        )
        val newRange = PriceScaleEngine.candleRange(
            newStart,
            newVisible,
            100,
            0f,
            400f
        )
        checkNotNull(newRange)

        assertEquals(6.75f, newStart, 0.0001f)
        assertEquals(focusX, PriceScaleEngine.xFor(13, newRange, 0f, 0f), 0.0001f)
        assertEquals(13, PriceScaleEngine.indexAt(focusX, newRange, 0f, 0f))
    }

    @Test
    fun verticalLayoutKeepsAllAreasPositiveWithTwoSubCharts() {
        listOf(240f, 120f, 80f).forEach { usable ->
            val layout = PriceScaleEngine.verticalLayout(
                0f,
                usable,
                2,
                24f,
                2f,
                60f,
                12f
            )
            checkNotNull(layout)

            assertTrue(layout.priceBottom > layout.priceTop)
            assertTrue(layout.volumeBottom >= layout.volumeTop)
            assertEquals(2, layout.panels.size)
            layout.panels.forEach { panel ->
                assertTrue(panel.bottom > panel.top)
                assertTrue(panel.bottom <= usable)
            }
        }
    }

    @Test
    fun multiDayIntradayRangeNeedsDateLabel() {
        assertTrue(PriceScaleEngine.needsDateLabel(1_000L, 1_000L + 172_800_000L))
        assertTrue(!PriceScaleEngine.needsDateLabel(1_000L, 1_000L + 3_600_000L))
        assertTrue(!PriceScaleEngine.needsDateLabel(0L, 172_800_000L))
    }

    @Test
    fun panDragDistanceMapsToCandleOffset() {
        val slot = 20f
        val rawLeft = PriceScaleEngine.dragOffset(10f, -100f, slot)
        val rawRight = PriceScaleEngine.dragOffset(10f, 100f, slot)

        assertEquals(15f, rawLeft, 0.0001f)
        assertEquals(5f, rawRight, 0.0001f)

        val result = PriceScaleEngine.panOffset(rawLeft, slot, 100f, 400f, 48f)
        assertEquals(15f, result.scrollOffset, 0.0001f)
        assertEquals(0f, result.overscroll, 0.0001f)
    }

    @Test
    fun flingProjectionFollowsVelocityAndStaysInBounds() {
        val leftFling = PriceScaleEngine.flingTargetOffset(20f, -500f, 20f, 100f, 0.16f)
        val rightFling = PriceScaleEngine.flingTargetOffset(20f, 500f, 20f, 100f, 0.16f)
        val clamped = PriceScaleEngine.flingTargetOffset(99f, -8_000f, 20f, 100f, 0.16f)

        assertEquals(24f, leftFling, 0.0001f)
        assertEquals(16f, rightFling, 0.0001f)
        assertEquals(100f, clamped, 0.0001f)
        assertTrue(leftFling > 20f)
        assertTrue(rightFling < 20f)
    }

    @Test
    fun panBeyondBoundsUsesRubberBandWithoutMovingOffset() {
        val result = PriceScaleEngine.panOffset(-8f, 20f, 100f, 400f, 48f)

        assertEquals(0f, result.scrollOffset, 0.0001f)
        assertTrue(result.overscroll > 0f)
        assertTrue(abs(result.overscroll) < 160f)
    }

    @Test
    fun axisZoomKeepsFingerPriceAnchored() {
        val start = PriceRange(100.0, 200.0)
        val height = 400f
        val y = 100f
        val anchor = PriceScaleEngine.priceForY(y, start, 0f, height)
        val ratio = ((height - y) / height).toDouble()

        val zoomIn = PriceScaleEngine.axisZoomRange(
            startRange = start,
            travel = PriceScaleEngine.axisZoomTravel(200f, 260f, 0f),
            height = height,
            anchorPrice = anchor,
            anchorRatio = ratio,
            minSpan = 1.0,
            maxSpan = 1_000.0,
            elastic = false
        )
        val zoomOut = PriceScaleEngine.axisZoomRange(
            startRange = start,
            travel = PriceScaleEngine.axisZoomTravel(200f, 140f, 0f),
            height = height,
            anchorPrice = anchor,
            anchorRatio = ratio,
            minSpan = 1.0,
            maxSpan = 1_000.0,
            elastic = false
        )

        assertEquals(175.0, anchor, 0.0001)
        assertTrue(zoomIn.span < start.span)
        assertTrue(zoomOut.span > start.span)
        assertEquals(anchor, PriceScaleEngine.priceForY(y, zoomIn, 0f, height), 1.0e-6)
        assertEquals(anchor, PriceScaleEngine.priceForY(y, zoomOut, 0f, height), 1.0e-6)
    }

    @Test
    fun axisZoomDirectionAndFlingFollowFinger() {
        val dragUp = PriceScaleEngine.axisZoomTravel(300f, 200f, 0f)
        val dragDown = PriceScaleEngine.axisZoomTravel(200f, 300f, 0f)
        val downFling = PriceScaleEngine.axisZoomTravel(200f, 300f, 400f, 0.10f)

        assertTrue(PriceScaleEngine.axisZoomFactor(dragUp, 400f) > 1.0)
        assertTrue(PriceScaleEngine.axisZoomFactor(dragDown, 400f) < 1.0)
        assertTrue(downFling < dragDown)
    }

    @Test
    fun pinchFocusStaysFixedForFractionalAnchor() {
        val oldRange = PriceScaleEngine.candleRange(0f, 40f, 100, 0f, 400f)
        checkNotNull(oldRange)

        val focusX = 136f
        val anchorIndex = oldRange.startFloat + focusX / oldRange.slot
        val newVisible = 20f
        val newSlot = 400f / newVisible
        val newStart = PriceScaleEngine.anchoredStart(
            anchorIndex,
            focusX,
            0f,
            newSlot,
            100,
            newVisible
        )
        val newRange = PriceScaleEngine.candleRange(newStart, newVisible, 100, 0f, 400f)
        checkNotNull(newRange)

        assertEquals(focusX, PriceScaleEngine.xFor(13, newRange, 0f, 0f), 0.0001f)
        assertEquals(13, PriceScaleEngine.indexAt(focusX, newRange, 0f, 0f))
    }

    @Test
    fun drawingAcceptsOnlyMainPricePlot() {
        val left = 4f
        val right = 338f
        val priceTop = 6f
        val priceBottom = 300f

        assertTrue(PriceScaleEngine.isInsidePlot(100f, 150f, left, right, priceTop, priceBottom))
        assertTrue(!PriceScaleEngine.isInsidePlot(100f, 320f, left, right, priceTop, priceBottom))
        assertTrue(!PriceScaleEngine.isInsidePlot(100f, 360f, left, right, priceTop, priceBottom))
        assertTrue(!PriceScaleEngine.isInsidePlot(360f, 150f, left, right, priceTop, priceBottom))
        assertTrue(!PriceScaleEngine.isInsidePlot(100f, 610f, left, right, priceTop, priceBottom))
        assertTrue(!PriceScaleEngine.isInsidePlot(2f, 150f, left, right, priceTop, priceBottom))
    }

    @Test
    fun drawingPositionSurvivesPanAndZoom() {
        val candleIndex = 25
        val price = 325.0
        val before = PriceScaleEngine.candleRange(0f, 40f, 100, 0f, 400f)
        checkNotNull(before)

        val xBefore = PriceScaleEngine.xFor(candleIndex, before, 0f, 0f)
        val afterPan = PriceScaleEngine.candleRange(5f, 40f, 100, 0f, 400f)
        checkNotNull(afterPan)
        val xAfterPan = PriceScaleEngine.xFor(candleIndex, afterPan, 0f, 0f)

        assertEquals(255f, xBefore, 0.0001f)
        assertEquals(205f, xAfterPan, 0.0001f)
        assertEquals(candleIndex, PriceScaleEngine.indexAt(xAfterPan, afterPan, 0f, 0f))

        val zoomed = PriceScaleEngine.candleRange(10f, 20f, 100, 0f, 400f)
        checkNotNull(zoomed)
        val xZoomed = PriceScaleEngine.xFor(candleIndex, zoomed, 0f, 0f)
        assertEquals(candleIndex, PriceScaleEngine.indexAt(xZoomed, zoomed, 0f, 0f))

        val zoomedRange = PriceRange(310.0, 390.0)
        val y = PriceScaleEngine.priceToY(price, zoomedRange, 0f, 300f)
        assertEquals(price, PriceScaleEngine.priceForY(y, zoomedRange, 0f, 300f), 1.0e-6)
    }
}
