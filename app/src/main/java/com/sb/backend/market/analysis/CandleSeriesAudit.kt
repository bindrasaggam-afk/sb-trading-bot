package com.sb.backend.market.analysis

import com.sb.backend.market.TimeframeBuckets
import com.sb.backend.market.model.MarketCandle
import kotlin.math.abs

/**
 * Compares independently sourced stored and live candle series.
 * It reports differences without mutating either source.
 */
object CandleSeriesAudit {

    data class Report(
        val storedValidCount: Int,
        val liveValidCount: Int,
        val matchedTimestamps: Int,
        val missingFromLive: Int,
        val missingFromStored: Int,
        val ohlcMismatches: Int,
        val volumeMismatches: Int,
        val duplicateStoredTimestamps: Int,
        val duplicateLiveTimestamps: Int
    ) {
        val exactMatch: Boolean
            get() = missingFromLive == 0 &&
                missingFromStored == 0 &&
                ohlcMismatches == 0 &&
                volumeMismatches == 0 &&
                duplicateStoredTimestamps == 0 &&
                duplicateLiveTimestamps == 0
    }

    fun compare(
        stored: List<MarketCandle>,
        live: List<MarketCandle>,
        priceTolerance: Double = 0.000001
    ): Report {
        require(priceTolerance >= 0.0 && priceTolerance.isFinite())
        val validStored = stored.filter { it.isValid }
        val validLive = live.filter { it.isValid }

        fun normalizedMap(rows: List<MarketCandle>): Map<Long, MarketCandle> =
            rows.associateBy { TimeframeBuckets.normalizeTimestamp(it.timestamp) }

        val storedMap = normalizedMap(validStored)
        val liveMap = normalizedMap(validLive)
        val common = storedMap.keys.intersect(liveMap.keys)

        var ohlcMismatches = 0
        var volumeMismatches = 0
        common.forEach { timestamp ->
            val a = storedMap.getValue(timestamp)
            val b = liveMap.getValue(timestamp)
            if (abs(a.open - b.open) > priceTolerance ||
                abs(a.high - b.high) > priceTolerance ||
                abs(a.low - b.low) > priceTolerance ||
                abs(a.close - b.close) > priceTolerance
            ) ohlcMismatches++
            if (a.volume != b.volume) volumeMismatches++
        }

        return Report(
            storedValidCount = validStored.size,
            liveValidCount = validLive.size,
            matchedTimestamps = common.size,
            missingFromLive = storedMap.keys.count { it !in liveMap },
            missingFromStored = liveMap.keys.count { it !in storedMap },
            ohlcMismatches = ohlcMismatches,
            volumeMismatches = volumeMismatches,
            duplicateStoredTimestamps = validStored.size - storedMap.size,
            duplicateLiveTimestamps = validLive.size - liveMap.size
        )
    }
}
