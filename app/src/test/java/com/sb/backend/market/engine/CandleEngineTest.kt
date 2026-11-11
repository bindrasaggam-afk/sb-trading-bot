package com.sb.backend.market.engine

import com.sb.backend.market.TimeframeBuckets
import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.market.model.MarketCandle
import com.sb.backend.market.model.MarketTick
import com.sb.backend.market.model.MarketTimeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CandleEngineTest {

    private val nse = InstrumentKey("NSE", "SBIN")
    private val mcx = InstrumentKey("MCX", "CRUDEOIL25NOVFUT")

    @Test
    fun firstTickCreatesOneMinuteCandleWithoutGuessedVolume() {
        val engine = CandleEngine()
        val result = engine.addTick(tick(nse, time(9, 15, 20), 100.0, 1_000L))

        assertTrue(result.accepted)
        assertEquals(time(9, 15, 0), result.baseTimestamp)
        assertEquals(1, engine.candles(nse, MarketTimeframe.MIN_1).size)

        val candle = engine.candles(nse, MarketTimeframe.MIN_1).first()
        assertEquals(100.0, candle.open, 0.0001)
        assertEquals(100.0, candle.high, 0.0001)
        assertEquals(100.0, candle.low, 0.0001)
        assertEquals(100.0, candle.close, 0.0001)
        assertEquals(0L, candle.volume)
    }

    @Test
    fun ticksUpdateOhlcAndUseCumulativeVolumeDelta() {
        val engine = CandleEngine()

        engine.addTick(tick(nse, time(9, 15, 5), 100.0, 1_000L))
        engine.addTick(tick(nse, time(9, 15, 20), 102.0, 1_200L))
        engine.addTick(tick(nse, time(9, 15, 40), 99.0, 1_250L))

        val candle = engine.candles(nse, MarketTimeframe.MIN_1).single()
        assertEquals(100.0, candle.open, 0.0001)
        assertEquals(102.0, candle.high, 0.0001)
        assertEquals(99.0, candle.low, 0.0001)
        assertEquals(99.0, candle.close, 0.0001)
        assertEquals(250L, candle.volume)
    }

    @Test
    fun newMinuteFinalizesPreviousCandle() {
        val engine = CandleEngine()

        engine.addTick(tick(nse, time(9, 15, 10), 100.0, 1_000L))
        val result = engine.addTick(tick(nse, time(9, 16, 10), 101.0, 1_100L))

        assertTrue(result.accepted)
        assertEquals(1, result.finalizedBase.size)
        assertEquals(time(9, 15, 0), result.finalizedBase.first().timestamp)
        assertEquals(100.0, result.finalizedBase.first().close, 0.0001)
        assertEquals(time(9, 16, 0), result.currentBase?.timestamp)
    }

    @Test
    fun duplicateTickIsIgnored() {
        val engine = CandleEngine()
        val tick = tick(nse, time(9, 15, 10), 100.0, 1_000L)

        assertTrue(engine.addTick(tick).accepted)
        assertFalse(engine.addTick(tick).accepted)
        assertEquals(0L, engine.candles(nse, MarketTimeframe.MIN_1).single().volume)
    }

    @Test
    fun outOfOrderTickKeepsCorrectOpenAndClose() {
        val engine = CandleEngine()

        engine.addTick(tick(nse, time(9, 15, 30), 105.0, 1_000L))
        engine.addTick(tick(nse, time(9, 15, 10), 100.0, 1_050L))

        val candle = engine.candles(nse, MarketTimeframe.MIN_1).single()
        assertEquals(100.0, candle.open, 0.0001)
        assertEquals(105.0, candle.high, 0.0001)
        assertEquals(100.0, candle.low, 0.0001)
        assertEquals(105.0, candle.close, 0.0001)
    }

    @Test
    fun volumeResetProducesFullVolumeForNewBaseline() {
        val engine = CandleEngine()

        engine.addTick(tick(nse, time(9, 15, 10), 100.0, 1_000L))
        engine.addTick(tick(nse, time(9, 15, 20), 100.5, 900L))

        assertEquals(900L, engine.candles(nse, MarketTimeframe.MIN_1).single().volume)
    }

    @Test
    fun instrumentsAreIsolated() {
        val engine = CandleEngine()

        engine.addTick(tick(nse, time(9, 15, 10), 100.0, 1_000L))
        engine.addTick(tick(mcx, time(9, 15, 10), 5_000.0, 2_000L))

        assertEquals(100.0, engine.candles(nse, MarketTimeframe.MIN_1).single().close, 0.0001)
        assertEquals(5_000.0, engine.candles(mcx, MarketTimeframe.MIN_1).single().close, 0.0001)
    }

    @Test
    fun fiveMinuteCandlesAggregateFromOneMinuteBase() {
        val engine = CandleEngine()

        engine.addTick(tick(nse, time(9, 15, 10), 100.0, 1_000L))
        engine.addTick(tick(nse, time(9, 16, 10), 104.0, 1_100L))
        engine.addTick(tick(nse, time(9, 17, 10), 98.0, 1_200L))

        val candle = engine.candles(nse, MarketTimeframe.MIN_5).single()
        assertEquals(time(9, 15, 0), candle.timestamp)
        assertEquals(100.0, candle.open, 0.0001)
        assertEquals(104.0, candle.high, 0.0001)
        assertEquals(98.0, candle.low, 0.0001)
        assertEquals(98.0, candle.close, 0.0001)
        assertEquals(200L, candle.volume)
    }

    @Test
    fun seededCandlesAreRestoredAndLiveCandlesAppend() {
        val engine = CandleEngine()
        val stored = MarketCandle(
            timestamp = time(9, 15, 0),
            open = 99.0,
            high = 101.0,
            low = 98.0,
            close = 100.0,
            volume = 500L
        )

        engine.seedBase(nse, listOf(stored))
        assertEquals(1, engine.candles(nse, MarketTimeframe.MIN_1).size)

        engine.addTick(tick(nse, time(9, 16, 5), 102.0, 1_000L))
        val candles = engine.candles(nse, MarketTimeframe.MIN_1)

        assertEquals(2, candles.size)
        assertEquals(100.0, candles[0].close, 0.0001)
        assertEquals(102.0, candles[1].open, 0.0001)
    }

    @Test
    fun invalidTicksNeverCreateCandles() {
        val engine = CandleEngine()

        assertFalse(engine.addTick(tick(nse, time(9, 15, 10), 0.0, 1_000L)).accepted)
        assertFalse(engine.addTick(tick(nse, time(9, 15, 10), Double.NaN, 1_000L)).accepted)
        assertTrue(engine.candles(nse, MarketTimeframe.MIN_1).isEmpty())
    }

    @Test
    fun intradayAndCalendarMonthLabelsRemainCaseSensitive() {
        assertEquals(MarketTimeframe.MIN_5, MarketTimeframe.fromLabel("5m"))
        assertEquals(null, MarketTimeframe.fromLabel("5M"))
        assertEquals(MarketTimeframe.MONTH_1, MarketTimeframe.fromLabel("1M"))
        assertEquals(null, MarketTimeframe.fromLabel("1m").takeIf { it == MarketTimeframe.MONTH_1 })
    }

    @Test
    fun timeframeBucketsAreSessionAligned() {
        assertEquals(time(9, 15, 0), TimeframeBuckets.bucketStart(time(9, 16, 0), "15m", "NSE"))
        assertEquals(time(9, 30, 0), TimeframeBuckets.bucketStart(time(9, 44, 59), "15m", "NSE"))
        assertEquals(time(10, 15, 0), TimeframeBuckets.bucketStart(time(10, 20, 0), "1h", "NSE"))
        assertEquals(time(11, 15, 0), TimeframeBuckets.bucketStart(time(11, 45, 0), "2h", "NSE"))
    }

    @Test
    fun mcxBucketsUseNineAmOpen() {
        assertEquals(time(9, 0, 0), TimeframeBuckets.bucketStart(time(9, 7, 0), "15m", "MCX"))
        assertEquals(time(9, 15, 0), TimeframeBuckets.bucketStart(time(9, 17, 0), "15m", "MCX"))
    }

    @Test
    fun calendarTimeframesAlignToBoundaries() {
        val monday = time(0, 0, 0, day = 5)
        val thursday = time(0, 0, 0, day = 8)

        assertEquals(monday, TimeframeBuckets.bucketStart(thursday, "1W", "NSE"))
        assertEquals(time(0, 0, 0, day = 1), TimeframeBuckets.bucketStart(thursday, "1M", "NSE"))
        assertEquals(time(0, 0, 0, day = 1), TimeframeBuckets.bucketStart(thursday, "1Y", "NSE"))
        assertEquals(time(0, 0, 0, day = 1), TimeframeBuckets.bucketStart(thursday, "3M", "NSE"))
    }

    @Test
    fun implausibleFutureTimestampFallsBackToReceiveTime() {
        val engine = CandleEngine()
        val received = time(9, 15, 20)
        val oneYearAhead = received + 365L * 24L * 60L * 60L * 1_000L

        val result = engine.addTick(
            MarketTick(
                key = mcx,
                ltp = 5_000.0,
                volume = 10L,
                lastTradeTime = oneYearAhead,
                receivedAt = received
            )
        )

        assertTrue(result.accepted)
        assertEquals(time(9, 15, 0), result.baseTimestamp)
    }

    @Test
    fun packedOrStaleTimestampFallsBackToReceiveTime() {
        val engine = CandleEngine()
        val received = time(9, 15, 20)

        val result = engine.addTick(
            MarketTick(
                key = mcx,
                ltp = 5_000.0,
                volume = 10L,
                lastTradeTime = 91_520L,
                receivedAt = received
            )
        )

        assertTrue(result.accepted)
        assertEquals(time(9, 15, 0), result.baseTimestamp)
    }

    @Test
    fun futureSeededCandleIsIgnoredAndDoesNotBlockLiveTicks() {
        val engine = CandleEngine()
        val now = System.currentTimeMillis()
        val future = now + 24L * 60L * 60L * 1_000L

        engine.seedBase(
            mcx,
            listOf(
                MarketCandle(
                    timestamp = future,
                    open = 5_000.0,
                    high = 5_010.0,
                    low = 4_990.0,
                    close = 5_005.0,
                    volume = 10L
                )
            )
        )

        assertTrue(engine.candles(mcx, MarketTimeframe.MIN_1).isEmpty())

        val result = engine.addTick(tick(mcx, now, 5_100.0, 100L))

        assertTrue(result.accepted)
        assertEquals(1, engine.candles(mcx, MarketTimeframe.MIN_1).size)
    }

    @Test
    fun higherTimeframeAggregatesSeededAndLiveBase() {
        val engine = CandleEngine()

        val stored = (0 until 10).map { i ->
            MarketCandle(
                timestamp = time(9, 15, 0) + i * 60_000L,
                open = 100.0 + i,
                high = 101.0 + i,
                low = 99.0 + i,
                close = 100.5 + i,
                volume = 10L
            )
        }

        engine.seedBase(nse, stored)
        engine.addTick(tick(nse, time(9, 25, 30), 120.0, 200L))

        val candle = engine.candles(nse, MarketTimeframe.MIN_15).single()

        assertEquals(time(9, 15, 0), candle.timestamp)
        assertEquals(100.0, candle.open, 0.0001)
        assertEquals(120.0, candle.high, 0.0001)
        assertEquals(99.0, candle.low, 0.0001)
        assertEquals(120.0, candle.close, 0.0001)
    }

    @Test
    fun candleAtReturnsAggregatedTargetTimeframe() {
        val engine = CandleEngine()

        engine.addTick(tick(nse, time(9, 15, 10), 100.0, 1_000L))
        engine.addTick(tick(nse, time(9, 16, 10), 105.0, 1_100L))
        engine.addTick(tick(nse, time(9, 16, 40), 98.0, 1_200L))

        val candle = engine.candleAt(
            nse,
            MarketTimeframe.MIN_5,
            time(9, 16, 0)
        )

        assertNotNull(candle)
        assertEquals(time(9, 15, 0), candle!!.timestamp)
        assertEquals(100.0, candle.open, 0.0001)
        assertEquals(105.0, candle.high, 0.0001)
        assertEquals(98.0, candle.low, 0.0001)
        assertEquals(98.0, candle.close, 0.0001)
    }

    @Test
    fun switchingTimeframeKeepsOneMinuteBaseIntact() {
        val engine = CandleEngine()

        engine.addTick(tick(nse, time(9, 15, 10), 100.0, 1_000L))
        engine.addTick(tick(nse, time(9, 16, 10), 101.0, 1_100L))
        engine.addTick(tick(nse, time(9, 17, 10), 102.0, 1_200L))

        assertEquals(3, engine.candles(nse, MarketTimeframe.MIN_1).size)
        assertEquals(1, engine.candles(nse, MarketTimeframe.MIN_5).size)
        assertEquals(1, engine.candles(nse, MarketTimeframe.MIN_15).size)
        assertEquals(1, engine.candles(nse, MarketTimeframe.HOUR_1).size)
        assertEquals(3, engine.candles(nse, MarketTimeframe.MIN_1).size)
    }

    @Test
    fun mcxSecondsTimestampBuildsCandleInSessionBucket() {
        val engine = CandleEngine()
        val received = time(9, 16, 30)
        val secondsTimestamp = time(9, 16, 20) / 1_000L

        val result = engine.addTick(
            MarketTick(
                key = mcx,
                ltp = 5_000.0,
                volume = 10L,
                lastTradeTime = secondsTimestamp,
                receivedAt = received
            )
        )

        assertTrue(result.accepted)
        assertEquals(time(9, 16, 0), result.baseTimestamp)
        assertEquals(1, engine.candles(mcx, MarketTimeframe.MIN_1).size)
        assertEquals(5_000.0, engine.candles(mcx, MarketTimeframe.MIN_1).single().close, 0.0001)
    }

    @Test
    fun mcxLiveTicksAggregateToFifteenMinuteCandle() {
        val engine = CandleEngine()

        engine.addTick(tick(mcx, time(9, 0, 10), 5_000.0, 1_000L))
        engine.addTick(tick(mcx, time(9, 7, 10), 5_010.0, 1_100L))
        engine.addTick(tick(mcx, time(9, 14, 10), 4_990.0, 1_200L))

        val candle = engine.candles(mcx, MarketTimeframe.MIN_15).single()

        assertEquals(time(9, 0, 0), candle.timestamp)
        assertEquals(5_000.0, candle.open, 0.0001)
        assertEquals(5_010.0, candle.high, 0.0001)
        assertEquals(4_990.0, candle.low, 0.0001)
        assertEquals(4_990.0, candle.close, 0.0001)
    }

    @Test
    fun volumeFallsBackToLastTradeQuantityWhenCumulativeMissing() {
        val engine = CandleEngine()

        engine.addTick(tick(nse, time(9, 15, 10), 100.0, 0L, lastTradeQty = 5L))
        engine.addTick(tick(nse, time(9, 15, 20), 101.0, 0L, lastTradeQty = 7L))

        assertEquals(12L, engine.candles(nse, MarketTimeframe.MIN_1).single().volume)
    }

    @Test
    fun missingVolumeIsNeverInventedForIndex() {
        val engine = CandleEngine()
        val index = InstrumentKey("NSE", "NIFTY 50")

        engine.addTick(
            MarketTick(
                key = index,
                ltp = 22_000.0,
                volume = 0L,
                lastTradeQty = 0L,
                lastTradeTime = time(9, 15, 10),
                receivedAt = time(9, 15, 10),
                isIndex = true
            )
        )

        assertEquals(0L, engine.candles(index, MarketTimeframe.MIN_1).single().volume)
    }

    @Test
    fun lateTickForClosedBucketIsRejected() {
        val engine = CandleEngine()

        engine.addTick(tick(nse, time(9, 15, 10), 100.0, 1_000L))
        engine.addTick(tick(nse, time(9, 16, 10), 101.0, 1_100L))

        val late = engine.addTick(tick(nse, time(9, 15, 30), 999.0, 1_050L))

        assertFalse(late.accepted)
        val first = engine.candles(nse, MarketTimeframe.MIN_1).first()
        assertEquals(time(9, 15, 0), first.timestamp)
        assertEquals(100.0, first.close, 0.0001)
    }

    @Test
    fun seededNewestCandleIsRefinalizedWithLiveUpdates() {
        val engine = CandleEngine()
        val stored = MarketCandle(
            timestamp = time(9, 15, 0),
            open = 99.0,
            high = 101.0,
            low = 98.0,
            close = 100.0,
            volume = 500L
        )

        engine.seedBase(nse, listOf(stored))
        engine.addTick(tick(nse, time(9, 15, 40), 103.0, 600L))

        val result = engine.addTick(tick(nse, time(9, 16, 5), 104.0, 700L))

        assertTrue(result.accepted)
        assertEquals(1, result.finalizedBase.size)
        assertEquals(time(9, 15, 0), result.finalizedBase.first().timestamp)
        assertEquals(103.0, result.finalizedBase.first().close, 0.0001)
    }

    @Test
    fun requiredIntradayTimeframesAreSessionAligned() {
        assertEquals(time(9, 15, 0), TimeframeBuckets.bucketStart(time(9, 17, 59), "3m", "NSE"))
        assertEquals(time(9, 18, 0), TimeframeBuckets.bucketStart(time(9, 18, 0), "3m", "NSE"))

        assertEquals(time(9, 15, 0), TimeframeBuckets.bucketStart(time(9, 24, 59), "10m", "NSE"))
        assertEquals(time(9, 25, 0), TimeframeBuckets.bucketStart(time(9, 25, 0), "10m", "NSE"))

        assertEquals(time(9, 15, 0), TimeframeBuckets.bucketStart(time(9, 44, 59), "30m", "NSE"))
        assertEquals(time(9, 45, 0), TimeframeBuckets.bucketStart(time(9, 45, 0), "30m", "NSE"))

        assertEquals(time(9, 15, 0), TimeframeBuckets.bucketStart(time(11, 14, 59), "2h", "NSE"))
        assertEquals(time(11, 15, 0), TimeframeBuckets.bucketStart(time(11, 15, 0), "2h", "NSE"))

        assertEquals(time(9, 15, 0), TimeframeBuckets.bucketStart(time(13, 14, 59), "4h", "NSE"))
        assertEquals(time(13, 15, 0), TimeframeBuckets.bucketStart(time(13, 15, 0), "4h", "NSE"))
    }

    @Test
    fun oneMinuteBoundaryIncludesFinalSecond() {
        assertEquals(time(9, 15, 0), TimeframeBuckets.bucketStart(time(9, 15, 59), "1m", "NSE"))
        assertEquals(time(9, 16, 0), TimeframeBuckets.bucketStart(time(9, 16, 0), "1m", "NSE"))

        assertEquals(time(9, 0, 0), TimeframeBuckets.bucketStart(time(9, 0, 59), "1m", "MCX"))
        assertEquals(time(9, 1, 0), TimeframeBuckets.bucketStart(time(9, 1, 0), "1m", "MCX"))
    }

    @Test
    fun finalizationAcrossGapReturnsOnlyExistingCandles() {
        val engine = CandleEngine()

        engine.addTick(tick(nse, time(9, 15, 10), 100.0, 1_000L))
        val result = engine.addTick(tick(nse, time(9, 20, 10), 101.0, 1_100L))

        assertTrue(result.accepted)
        assertEquals(1, result.finalizedBase.size)
        assertEquals(time(9, 15, 0), result.finalizedBase.first().timestamp)
    }

    private fun tick(
        key: InstrumentKey,
        timestamp: Long,
        ltp: Double,
        volume: Long,
        lastTradeQty: Long = 1L
    ): MarketTick {
        return MarketTick(
            key = key,
            ltp = ltp,
            volume = volume,
            lastTradeQty = lastTradeQty,
            lastTradeTime = timestamp,
            receivedAt = timestamp
        )
    }

    private fun time(
        hour: Int,
        minute: Int,
        second: Int,
        day: Int = 5
    ): Long {
        val calendar = Calendar.getInstance(TimeframeBuckets.IST).apply {
            clear()
            set(2026, Calendar.JANUARY, day, hour, minute, second)
        }
        return calendar.timeInMillis
    }
}
