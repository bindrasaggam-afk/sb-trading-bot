package com.sb.backend.market.analysis

import com.sb.backend.market.TimeframeBuckets
import com.sb.backend.market.model.MarketCandle

/**
 * Reconciles candle series without manufacturing OHLC values.
 * Sources are applied from lowest to highest priority; later valid candles
 * replace earlier candles with the same normalized timestamp as a whole row.
 */
object CandleSeriesReconciler {

    fun merge(
        sources: List<List<MarketCandle>>,
        limit: Int = 2_000
    ): List<MarketCandle> {
        if (limit <= 0) return emptyList()
        val byTimestamp = LinkedHashMap<Long, MarketCandle>()
        sources.forEach { source ->
            source.forEach { candle ->
                val normalizedTime = TimeframeBuckets.normalizeTimestamp(candle.timestamp)
                val normalized = if (normalizedTime == candle.timestamp) candle
                    else candle.copy(timestamp = normalizedTime)
                if (normalized.isValid) {
                    byTimestamp[normalized.timestamp] = normalized
                }
            }
        }
        return byTimestamp.values.sortedBy { it.timestamp }.takeLast(limit)
    }
}
