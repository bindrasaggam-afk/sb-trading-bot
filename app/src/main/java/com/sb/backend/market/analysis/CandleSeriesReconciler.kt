package com.sb.backend.market.analysis

import com.sb.backend.market.TimeframeBuckets
import com.sb.backend.market.model.MarketCandle

/**
 * Reconciles candle series without manufacturing OHLC values.
 * Sources are applied from lowest to highest priority; later valid candles
 * replace earlier candles with the same normalized timestamp as a whole row.
 * When a timeframe label is supplied, timestamps are bucket-aligned first so
 * historical and live rows cannot duplicate each other at near boundaries.
 */
object CandleSeriesReconciler {

    fun merge(
        sources: List<List<MarketCandle>>,
        limit: Int = 2_000,
        timeframeLabel: String? = null,
        exchange: String = ""
    ): List<MarketCandle> {
        if (limit <= 0) return emptyList()
        val byTimestamp = LinkedHashMap<Long, MarketCandle>()
        sources.forEach sourceLoop@{ source ->
            source.forEach candleLoop@{ candle ->
                var normalizedTime = TimeframeBuckets.normalizeTimestamp(candle.timestamp)
                if (normalizedTime <= 0L) return@candleLoop

                if (!timeframeLabel.isNullOrBlank()) {
                    normalizedTime = TimeframeBuckets.bucketStart(
                        timestamp = normalizedTime,
                        label = timeframeLabel,
                        exchange = exchange
                    )
                    if (normalizedTime <= 0L) return@candleLoop
                }

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
