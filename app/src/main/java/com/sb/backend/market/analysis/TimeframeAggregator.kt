package com.sb.backend.market.analysis

import com.sb.backend.market.TimeframeBuckets
import com.sb.backend.market.model.MarketTimeframe
import com.sb.backend.models.Candle
import kotlin.math.max
import kotlin.math.min

object TimeframeAggregator {

    fun aggregate(
        source: List<Candle>,
        targetTimeframe: MarketTimeframe,
        targetLabel: String,
        exchange: String
    ): List<Candle> {
        val normalized = source.mapNotNull { candle ->
            val timestamp = TimeframeBuckets.normalizeTimestamp(candle.timestamp)
            if (timestamp <= 0L) {
                null
            } else {
                timestamp to candle
            }
        }.sortedBy { it.first }

        val buckets = LinkedHashMap<Long, Candle>()

        normalized.forEach { (timestamp, candle) ->
            val bucket = TimeframeBuckets.bucketStart(
                timestamp = timestamp,
                timeframe = targetTimeframe,
                exchange = exchange
            )

            val existing = buckets[bucket]
            buckets[bucket] = if (existing == null) {
                candle.copy(timestamp = bucket, timeframe = targetLabel)
            } else {
                existing.copy(
                    high = max(existing.high, candle.high),
                    low = min(existing.low, candle.low),
                    close = candle.close,
                    volume = existing.volume + candle.volume
                )
            }
        }

        return buckets.values.toList()
    }
}
