package com.sb.backend.market.analysis

import com.sb.backend.market.model.MarketCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandleSeriesAuditTest {
    private fun c(t: Long, o: Double, h: Double, l: Double, close: Double, v: Long = 10L) =
        MarketCandle(t, o, h, l, close, v)

    @Test fun reportsExactMatchForIdenticalSeries() {
        val rows = listOf(
            c(1_700_000_000_000L, 100.0, 102.0, 99.0, 101.0),
            c(1_700_000_060_000L, 101.0, 103.0, 100.0, 102.0)
        )
        val report = CandleSeriesAudit.compare(rows, rows)
        assertTrue(report.exactMatch)
        assertEquals(2, report.matchedTimestamps)
    }

    @Test fun reportsMissingRowsOhlcVolumeAndDuplicates() {
        val stored = listOf(
            c(1_700_000_000_000L, 100.0, 102.0, 99.0, 101.0, 10L),
            c(1_700_000_000_000L, 100.0, 102.0, 99.0, 101.0, 10L),
            c(1_700_000_060_000L, 101.0, 103.0, 100.0, 102.0, 10L)
        )
        val live = listOf(
            c(1_700_000_000_000L, 100.0, 103.0, 99.0, 102.0, 15L),
            c(1_700_000_120_000L, 102.0, 104.0, 101.0, 103.0, 10L)
        )
        val report = CandleSeriesAudit.compare(stored, live)
        assertEquals(1, report.duplicateStoredTimestamps)
        assertEquals(1, report.missingFromLive)
        assertEquals(1, report.missingFromStored)
        assertEquals(1, report.ohlcMismatches)
        assertEquals(1, report.volumeMismatches)
        assertFalse(report.exactMatch)
    }
}
