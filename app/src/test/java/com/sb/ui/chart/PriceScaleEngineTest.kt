package com.sb.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceScaleEngineTest {

    @Test
    fun autoFitRangeKeepsCommodityScaleAndPadding() {
        val range = PriceScaleEngine.autoFitRange(318.0, 340.0)

        assertTrue(range.isValid())
        assertTrue(range.min < 318.0)
        assertTrue(range.max > 340.0)
        assertEquals(22.0 * 1.16, range.span, 0.0001)
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
}
