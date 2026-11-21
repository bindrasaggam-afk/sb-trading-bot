package com.sb.backend.market.analysis

import com.sb.backend.market.TimeframeBuckets
import com.sb.backend.market.model.MarketCandle
import com.sb.backend.market.model.MarketTimeframe
import com.sb.backend.models.Candle
import com.sb.signals.domain.market.CandleSeriesTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Models an independent vendor feed with literal, hand-authored rollups.
 * The source rows are deterministic fixtures; expected higher-timeframe bars
 * are never produced by TimeframeAggregator or CandleSeriesTools.
 */
class IndependentHistoricalBarsCrossCheckTest {

    private data class VendorBar(
        val timestamp: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long
    )

    @Test
    fun vendorOneMinuteRowsAreValidUniqueAndAscending() {
        val rows = vendorOneMinuteRows()

        assertEquals(60, rows.size)
        assertEquals(rows.size, rows.map { it.timestamp }.toSet().size)
        assertTrue(rows.zipWithNext().all { (a, b) -> b.timestamp > a.timestamp })
        rows.forEach { row ->
            assertNotNull(row.asMarketCandle())
            assertTrue(MarketCandle.isValid(row.timestamp, row.open, row.high, row.low, row.close, row.volume))
            assertTrue(row.high >= maxOf(row.open, row.close))
            assertTrue(row.low <= minOf(row.open, row.close))
            assertTrue(row.volume >= 0L)
        }
    }

    @Test
    fun oneMinuteChainMatchesIndependentVendorFiveMinuteBars() {
        val actual = TimeframeAggregator.aggregate(
            vendorOneMinuteRows().map { it.asCandle() },
            MarketTimeframe.MIN_5,
            "5m",
            EXCHANGE
        )

        assertExactMatch(actual.map { it.asVendorBar() }, vendorFiveMinuteBars(), 12)
        assertEquals(VENDOR_5M.first(), actual.first().asVendorBar())
        assertEquals(VENDOR_5M.last(), actual.last().asVendorBar())
    }

    @Test
    fun fiveMinuteChainMatchesIndependentVendorFifteenMinuteBars() {
        val actual = TimeframeAggregator.aggregate(
            vendorFiveMinuteBars().map { it.asCandle() },
            MarketTimeframe.MIN_15,
            "15m",
            EXCHANGE
        )

        assertExactMatch(actual.map { it.asVendorBar() }, vendorFifteenMinuteBars(), 4)
        assertEquals(VENDOR_15M.first(), actual.first().asVendorBar())
        assertEquals(VENDOR_15M.last(), actual.last().asVendorBar())
    }

    @Test
    fun fifteenMinuteChainMatchesIndependentVendorHourBar() {
        val actual = TimeframeAggregator.aggregate(
            vendorFifteenMinuteBars().map { it.asCandle() },
            MarketTimeframe.HOUR_1,
            "1h",
            EXCHANGE
        )

        assertExactMatch(actual.map { it.asVendorBar() }, vendorHourBar(), 1)
        assertEquals(VENDOR_1H.single(), actual.single().asVendorBar())
    }

    @Test
    fun fullChainDirectHourAndCandleSeriesToolsAgreeWithIndependentVendorHour() {
        val oneMinute = vendorOneMinuteRows().map { it.asCandle() }
        val fiveMinute = TimeframeAggregator.aggregate(oneMinute, MarketTimeframe.MIN_5, "5m", EXCHANGE)
        val fifteenMinute = TimeframeAggregator.aggregate(fiveMinute, MarketTimeframe.MIN_15, "15m", EXCHANGE)
        val chainedHour = TimeframeAggregator.aggregate(fifteenMinute, MarketTimeframe.HOUR_1, "1h", EXCHANGE)
        val directHour = TimeframeAggregator.aggregate(oneMinute, MarketTimeframe.HOUR_1, "1h", EXCHANGE)
        val toolsHour = CandleSeriesTools.aggregate(
            vendorOneMinuteRows().mapNotNull { it.asMarketCandle() },
            "1h",
            EXCHANGE
        )

        assertExactMatch(chainedHour.map { it.asVendorBar() }, vendorHourBar(), 1)
        assertExactMatch(directHour.map { it.asVendorBar() }, chainedHour.map { it.asVendorBar() }, 1)
        assertExactMatch(toolsHour.map { it.toVendorBar() }, vendorHourBar(), 1)
        assertEquals(VENDOR_1H.single(), chainedHour.single().asVendorBar())
    }

    @Test
    fun auditDetectsSingleIndependentBarDrift() {
        val drifted = vendorHourBar().mapIndexed { index, bar ->
            if (index == 0) {
                bar.copy(close = bar.close + 0.01, volume = bar.volume + 1)
            } else {
                bar
            }
        }

        val report = CandleSeriesAudit.compare(
            stored = drifted.mapNotNull { it.asMarketCandle() },
            live = vendorHourBar().mapNotNull { it.asMarketCandle() }
        )

        assertFalse(report.exactMatch)
        assertEquals(1, report.ohlcMismatches)
        assertEquals(1, report.volumeMismatches)
        assertEquals(1, report.matchedTimestamps)
    }

    private fun assertExactMatch(
        actual: List<VendorBar>,
        expected: List<VendorBar>,
        expectedCount: Int
    ) {
        val report = CandleSeriesAudit.compare(
            stored = actual.mapNotNull { it.asMarketCandle() },
            live = expected.mapNotNull { it.asMarketCandle() }
        )
        assertTrue(report.exactMatch)
        assertEquals(expectedCount, report.storedValidCount)
        assertEquals(expectedCount, report.liveValidCount)
        assertEquals(expectedCount, report.matchedTimestamps)
        assertEquals(0, report.missingFromLive)
        assertEquals(0, report.missingFromStored)
        assertEquals(0, report.ohlcMismatches)
        assertEquals(0, report.volumeMismatches)
        assertEquals(0, report.duplicateStoredTimestamps)
        assertEquals(0, report.duplicateLiveTimestamps)
        assertEquals(expected, actual)
    }

    private fun vendorOneMinuteRows(): List<VendorBar> {
        val patterns = listOf(
            doubleArrayOf(0.0, 1.5, -0.5, 0.5, 10.0),
            doubleArrayOf(0.5, 2.0, 0.0, 1.0, 11.0),
            doubleArrayOf(1.0, 1.5, -1.0, 0.25, 12.0),
            doubleArrayOf(0.25, 0.75, -0.5, -0.25, 13.0),
            doubleArrayOf(-0.25, 1.5, -0.5, 1.0, 14.0)
        )
        val rows = ArrayList<VendorBar>(60)
        for (group in 0 until 12) {
            val price = (100 + group).toDouble()
            val groupStart = BASE_TIMESTAMP + group * 5L * MINUTE
            patterns.forEachIndexed { minute, pattern ->
                rows.add(
                    VendorBar(
                        timestamp = groupStart + minute * MINUTE,
                        open = price + pattern[0],
                        high = price + pattern[1],
                        low = price + pattern[2],
                        close = price + pattern[3],
                        volume = pattern[4].toLong()
                    )
                )
            }
        }
        return rows
    }

    private fun vendorFiveMinuteBars(): List<VendorBar> = VENDOR_5M

    private fun vendorFifteenMinuteBars(): List<VendorBar> = VENDOR_15M

    private fun vendorHourBar(): List<VendorBar> = VENDOR_1H

    private fun VendorBar.asCandle(): Candle = Candle(
        symbol = SYMBOL,
        timeframe = "1m",
        timestamp = timestamp,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        isComplete = true,
        exchange = EXCHANGE
    )

    private fun Candle.asVendorBar(): VendorBar = VendorBar(
        timestamp = timestamp,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume
    )

    private fun MarketCandle.toVendorBar(): VendorBar = VendorBar(
        timestamp = timestamp,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume
    )

    private fun VendorBar.asMarketCandle(): MarketCandle? = MarketCandle.create(
        timestamp = timestamp,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume
    )

    private companion object {
        const val SYMBOL = "TESTBANK"
        const val EXCHANGE = "NSE"
        const val MINUTE = 60_000L
        const val BASE_TIMESTAMP = 1_736_307_900_000L

        val VENDOR_5M = listOf(
            VendorBar(1_736_307_900_000L, 100.0, 102.0, 99.0, 101.0, 60L),
            VendorBar(1_736_308_200_000L, 101.0, 103.0, 100.0, 102.0, 60L),
            VendorBar(1_736_308_500_000L, 102.0, 104.0, 101.0, 103.0, 60L),
            VendorBar(1_736_308_800_000L, 103.0, 105.0, 102.0, 104.0, 60L),
            VendorBar(1_736_309_100_000L, 104.0, 106.0, 103.0, 105.0, 60L),
            VendorBar(1_736_309_400_000L, 105.0, 107.0, 104.0, 106.0, 60L),
            VendorBar(1_736_309_700_000L, 106.0, 108.0, 105.0, 107.0, 60L),
            VendorBar(1_736_310_000_000L, 107.0, 109.0, 106.0, 108.0, 60L),
            VendorBar(1_736_310_300_000L, 108.0, 110.0, 107.0, 109.0, 60L),
            VendorBar(1_736_310_600_000L, 109.0, 111.0, 108.0, 110.0, 60L),
            VendorBar(1_736_310_900_000L, 110.0, 112.0, 109.0, 111.0, 60L),
            VendorBar(1_736_311_200_000L, 111.0, 113.0, 110.0, 112.0, 60L)
        )

        val VENDOR_15M = listOf(
            VendorBar(1_736_307_900_000L, 100.0, 104.0, 99.0, 103.0, 180L),
            VendorBar(1_736_308_800_000L, 103.0, 107.0, 102.0, 106.0, 180L),
            VendorBar(1_736_309_700_000L, 106.0, 110.0, 105.0, 109.0, 180L),
            VendorBar(1_736_310_600_000L, 109.0, 113.0, 108.0, 112.0, 180L)
        )

        val VENDOR_1H = listOf(
            VendorBar(1_736_307_900_000L, 100.0, 113.0, 99.0, 112.0, 720L)
        )
    }
}
