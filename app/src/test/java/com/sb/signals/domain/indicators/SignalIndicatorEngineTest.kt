package com.sb.signals.domain.indicators

import com.sb.backend.market.TimeframeBuckets
import com.sb.backend.market.model.MarketCandle
import com.sb.signals.TestSignals
import com.sb.signals.domain.StrategyDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import kotlin.math.sqrt

class SignalIndicatorEngineTest {

    @Test
    fun adxRisesForOneWayTrend() {
        val candles = TestSignals.backendCandles(
            TestSignals.candles(
                count = 80,
                start = TestSignals.testNow - 80 * 300_000L,
                intervalMillis = 300_000L,
                step = 0.5
            )
        )
        val result = SignalIndicatorEngine.adx(candles, 14)
        val last = result.adx.last()
        assertNotNull(last)
        assertTrue(last!! > 0.0)
        assertTrue(result.plusDi.last()!! > result.minusDi.last()!!)
    }

    @Test
    fun bollingerUsesPopulationDeviation() {
        val result = SignalIndicatorEngine.bollinger(
            values = listOf(1.0, 2.0, 3.0, 4.0, 5.0),
            period = 5,
            multiplier = 2.0
        )
        assertEquals(3.0, result.middle.last()!!, 0.000001)
        val expected = 2.0 * sqrt(2.0)
        assertEquals(3.0 + expected, result.upper.last()!!, 0.000001)
        assertEquals(3.0 - expected, result.lower.last()!!, 0.000001)
    }

    @Test
    fun priceSmaUsesClosingPricesAndIsSeparateFromVolumeAverage() {
        val candles = TestSignals.candles(
            count = 4,
            start = TestSignals.testNow - 4 * 300_000L,
            intervalMillis = 300_000L,
            startPrice = 100.0,
            step = 1.0,
            volume = 100L
        )
        val config = StrategyDefaults.intraday("5m").copy(
            smaPeriod = 3,
            volumePeriod = 2
        )

        val result = SignalIndicatorEngine.compute(candles, config)

        assertEquals(
            (candles[1].close + candles[2].close + candles[3].close) / 3.0,
            result.values["SMA3"]!!,
            0.000001
        )
        assertEquals(
            ((candles[2].volume + candles[3].volume) / 2.0).toDouble(),
            result.values["VolumeMA2"]!!,
            0.000001
        )
        assertEquals(
            config.smaPeriod.toDouble(),
            SignalIndicatorEngine.indicatorParameters(config)["SMA Period"]!!,
            0.000001
        )
        assertEquals(
            config.supertrendMultiplier,
            SignalIndicatorEngine.indicatorParameters(config)
                ["Supertrend Multiplier"]!!,
            0.000001
        )
    }

    @Test
    fun sessionVwapResetsAtNewSession() {
        val dayOne = dayMillis(2025, Calendar.JANUARY, 8, 9, 15)
        val dayTwo = dayMillis(2025, Calendar.JANUARY, 9, 9, 15)
        val candles = listOf(
            MarketCandle(dayOne, 100.0, 101.0, 99.0, 100.0, 10),
            MarketCandle(dayOne + 300_000L, 102.0, 103.0, 101.0, 102.0, 30),
            MarketCandle(dayTwo, 200.0, 202.0, 198.0, 200.0, 20)
        )

        val vwap = SignalIndicatorEngine.sessionVwap(candles)
        assertEquals(100.0, vwap[0]!!, 0.000001)
        assertEquals(
            (100.0 * 10.0 + 102.0 * 30.0) / 40.0,
            vwap[1]!!,
            0.000001
        )
        assertEquals(200.0, vwap[2]!!, 0.000001)
    }

    @Test
    fun volumeSmaUsesOnlyCompletedWindow() {
        val start = TestSignals.testNow - 4 * 300_000L
        val candles = listOf(100L, 200L, 300L, 400L).mapIndexed {
                index,
                volume ->
            MarketCandle(
                timestamp = start + index * 300_000L,
                open = 100.0,
                high = 101.0,
                low = 99.0,
                close = 100.0,
                volume = volume
            )
        }
        val values = SignalIndicatorEngine.volumeSma(candles, 2)
        assertEquals(null, values[0])
        assertEquals(150.0, values[1]!!, 0.000001)
        assertEquals(250.0, values[2]!!, 0.000001)
        assertEquals(350.0, values[3]!!, 0.000001)
    }

    private fun dayMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): Long {
        return Calendar.getInstance(TimeframeBuckets.IST).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis
    }
}
