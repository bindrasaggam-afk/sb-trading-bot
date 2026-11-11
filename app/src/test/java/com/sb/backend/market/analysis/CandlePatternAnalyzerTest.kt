package com.sb.backend.market.analysis

import com.sb.backend.market.model.MarketCandle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandlePatternAnalyzerTest {
    private fun c(t: Long, o: Double, h: Double, l: Double, close: Double) =
        MarketCandle(t, o, h, l, close, 100L)

    @Test fun detectsDojiBySmallBodyRatio() {
        assertTrue(CandlePatternAnalyzer.isDoji(c(1, 100.0, 102.0, 98.0, 100.2)))
        assertFalse(CandlePatternAnalyzer.isDoji(c(1, 100.0, 102.0, 98.0, 101.0)))
    }

    @Test fun detectsHammerGeometry() {
        assertTrue(CandlePatternAnalyzer.isHammer(c(1, 100.0, 100.5, 97.0, 100.2)))
        assertFalse(CandlePatternAnalyzer.isHammer(c(1, 100.0, 103.0, 99.5, 100.2)))
    }

    @Test fun detectsShootingStarGeometry() {
        assertTrue(CandlePatternAnalyzer.isShootingStar(c(1, 100.0, 103.0, 99.8, 100.2)))
        assertFalse(CandlePatternAnalyzer.isShootingStar(c(1, 100.0, 100.5, 97.0, 100.2)))
    }

    @Test fun detectsBullishAndBearishEngulfing() {
        assertTrue(CandlePatternAnalyzer.isBullishEngulfing(
            c(1, 105.0, 106.0, 99.0, 100.0),
            c(2, 99.0, 107.0, 98.0, 106.0)
        ))
        assertTrue(CandlePatternAnalyzer.isBearishEngulfing(
            c(1, 100.0, 106.0, 99.0, 105.0),
            c(2, 106.0, 107.0, 98.0, 99.0)
        ))
    }

    @Test fun detectsMorningAndEveningStar() {
        assertTrue(CandlePatternAnalyzer.isMorningStar(
            c(1, 110.0, 111.0, 99.0, 100.0),
            c(2, 100.0, 102.0, 98.0, 100.5),
            c(3, 101.0, 108.0, 100.0, 107.0)
        ))
        assertTrue(CandlePatternAnalyzer.isEveningStar(
            c(1, 100.0, 111.0, 99.0, 110.0),
            c(2, 110.0, 112.0, 108.0, 110.5),
            c(3, 109.0, 110.0, 102.0, 103.0)
        ))
    }

    @Test fun rejectsInvalidCandles() {
        assertFalse(CandlePatternAnalyzer.isDoji(c(0, 100.0, 102.0, 98.0, 100.0)))
        assertFalse(CandlePatternAnalyzer.isHammer(c(1, 100.0, 99.0, 101.0, 100.2)))
    }
}
