package com.sb.ui.chart

import com.sb.backend.market.analysis.CandleRow
import com.sb.backend.market.analysis.CandleRowMapper
import com.sb.backend.market.analysis.CandleSeriesAudit
import com.sb.backend.market.model.MarketCandle
import com.sb.backend.models.Candle
import com.sb.signals.domain.market.CandleSeriesTools
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartOhlcvEquivalenceTest {

    private data class TestRow(
        val symbol: String = "TEST",
        val exchange: String = "NSE",
        val timeframe: String = "5m",
        val timestamp: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long,
        val status: String = "COMPLETED"
    ) : CandleRow {
        override fun string(index: Int): String? = when (index) {
            0 -> symbol
            1 -> exchange
            2 -> timeframe
            9 -> status
            else -> null
        }

        override fun long(index: Int): Long = when (index) {
            3 -> timestamp
            8 -> volume
            else -> 0L
        }

        override fun double(index: Int): Double = when (index) {
            4 -> open
            5 -> high
            6 -> low
            7 -> close
            else -> 0.0
        }
    }

    private fun baseTimestamp(): Long {
        val calendar = Calendar.getInstance(com.sb.backend.market.TimeframeBuckets.IST).apply {
            clear()
            set(2025, Calendar.JANUARY, 8, 9, 15, 0)
        }
        return calendar.timeInMillis
    }

    private fun rowCandles(rows: List<CandleRow>): List<Candle> =
        rows.map(CandleRowMapper::read)

    private fun marketCandles(candles: List<Candle>): List<MarketCandle> =
        candles.mapNotNull(CandleSeriesTools::toMarketCandle)

    private fun chartCandles(candles: List<MarketCandle>): List<ChartCandle> =
        candles.map {
            ChartCandle(
                timestamp = it.timestamp,
                open = it.open,
                high = it.high,
                low = it.low,
                close = it.close,
                volume = it.volume
            )
        }

    private fun backToMarket(candles: List<ChartCandle>): List<MarketCandle> =
        candles.map {
            MarketCandle(
                timestamp = it.timestamp,
                open = it.open,
                high = it.high,
                low = it.low,
                close = it.close,
                volume = it.volume
            )
        }

    @Test
    fun dbRowsReachChartWithoutOhlcvDrift() {
        val base = baseTimestamp()
        val rows = listOf(
            TestRow(
                timestamp = base,
                open = 100.0,
                high = 102.0,
                low = 99.0,
                close = 101.0,
                volume = 10L
            ),
            TestRow(
                timestamp = base + 300_000L,
                open = 101.0,
                high = 104.0,
                low = 100.0,
                close = 103.0,
                volume = 12L,
                status = "LIVE"
            ),
            TestRow(
                timestamp = base + 600_000L,
                open = 103.0,
                high = 105.0,
                low = 102.0,
                close = 104.0,
                volume = 14L
            )
        )

        val candles = rowCandles(rows)
        assertFalse(candles[1].isComplete)

        val stored = marketCandles(candles)
        val chart = ChartCandleSeries.canonicalize(chartCandles(stored))
        val report = CandleSeriesAudit.compare(stored, backToMarket(chart))

        assertTrue(report.exactMatch)
        assertEquals(3, report.matchedTimestamps)
        assertEquals(stored, backToMarket(chart))
    }

    @Test
    fun canonicalizeDropsInvalidDbRowsAndKeepsLastDuplicate() {
        val base = baseTimestamp()
        val rows = listOf(
            TestRow(
                timestamp = 0L,
                open = 100.0,
                high = 101.0,
                low = 99.0,
                close = 100.0,
                volume = 1L
            ),
            TestRow(
                timestamp = base,
                open = 100.0,
                high = 102.0,
                low = 99.0,
                close = 101.0,
                volume = 10L
            ),
            TestRow(
                timestamp = base,
                open = 101.0,
                high = 103.0,
                low = 100.0,
                close = 102.0,
                volume = 11L
            ),
            TestRow(
                timestamp = base + 300_000L,
                open = 0.0,
                high = 0.0,
                low = 0.0,
                close = 0.0,
                volume = 0L
            )
        )

        val chart = ChartCandleSeries.canonicalize(
            chartCandles(marketCandles(rowCandles(rows)))
        )

        assertEquals(1, chart.size)
        assertEquals(base, chart.single().timestamp)
        assertEquals(101.0, chart.single().open, 0.0)
        assertEquals(102.0, chart.single().close, 0.0)
        assertEquals(11L, chart.single().volume)
    }
}
