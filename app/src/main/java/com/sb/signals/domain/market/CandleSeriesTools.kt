package com.sb.signals.domain.market

import com.sb.backend.market.TimeframeBuckets
import com.sb.backend.market.model.MarketCandle
import com.sb.backend.market.model.MarketTimeframe
import com.sb.backend.models.Candle
import com.sb.signals.domain.DataQuality
import java.util.Locale

data class PreparedSeries(
    val all: List<MarketCandle>,
    val closed: List<MarketCandle>,
    val quality: DataQuality
)

object CandleSeriesTools {

    fun toMarketCandle(candle: Candle): MarketCandle? {
        if (!MarketCandle.isValid(
                timestamp = candle.timestamp,
                open = candle.open,
                high = candle.high,
                low = candle.low,
                close = candle.close,
                volume = candle.volume
            )
        ) {
            return null
        }
        return MarketCandle(
            timestamp = candle.timestamp,
            open = candle.open,
            high = candle.high,
            low = candle.low,
            close = candle.close,
            volume = candle.volume
        )
    }

    fun prepare(
        raw: List<Candle>,
        timeframe: String,
        exchange: String,
        now: Long,
        minCount: Int
    ): PreparedSeries {
        var invalidCount = raw.size
        val converted = raw.mapNotNull { candle ->
            toMarketCandle(candle)
        }
        invalidCount -= converted.size
        return prepareMarket(
            raw = converted,
            timeframe = timeframe,
            exchange = exchange,
            now = now,
            minCount = minCount,
            invalidCount = invalidCount
        )
    }

    fun prepareMarket(
        raw: List<MarketCandle>,
        timeframe: String,
        exchange: String,
        now: Long,
        minCount: Int,
        invalidCount: Int = 0
    ): PreparedSeries {
        var invalid = invalidCount
        val converted = raw.filter {
            if (it.isValid) {
                true
            } else {
                invalid++
                false
            }
        }

        val grouped = linkedMapOf<Long, MarketCandle>()
        converted.forEach { grouped[it.timestamp] = it }
        val duplicates = converted.size - grouped.size

        val sorted = grouped.values.sortedBy { it.timestamp }
        val closed = sorted.filter {
            isClosed(it.timestamp, timeframe, exchange, now)
        }

        val notes = ArrayList<String>()
        if (invalid > 0) notes.add("$invalid invalid candles ignored")
        if (duplicates > 0) notes.add("$duplicates duplicate candles ignored")
        if (closed.size < minCount) {
            notes.add("Need at least $minCount closed candles")
        }

        val last = closed.lastOrNull()
        val gapCount = countSuspiciousGaps(closed, timeframe)
        if (gapCount > 0) notes.add("$gapCount suspicious candle gaps detected")
        val staleLimitMillis = when (timeframe) {
            "1M", "3M" -> 45L * 86_400_000L
            "1W", "2W", "4W" -> 14L * 86_400_000L
            else -> if (isIntraday(timeframe)) {
                3L * 86_400_000L
            } else {
                10L * 86_400_000L
            }
        }
        val stale = last == null || now - last.timestamp > staleLimitMillis
        if (stale) notes.add("Candle series is stale or empty")

        var score = 100.0
        score -= minOf(30.0, duplicates * 5.0 + invalid * 5.0)
        if (closed.size < minCount) score -= 40.0
        if (stale) score -= 40.0
        score -= minOf(30.0, gapCount * 6.0)

        return PreparedSeries(
            all = sorted,
            closed = closed,
            quality = DataQuality(
                score = score.coerceIn(0.0, 100.0),
                candleCount = closed.size,
                firstTimestamp = closed.firstOrNull()?.timestamp,
                lastTimestamp = last?.timestamp,
                notes = notes
            )
        )
    }


    private fun countSuspiciousGaps(
        candles: List<MarketCandle>,
        timeframe: String
    ): Int {
        if (candles.size < 2) return 0
        val parsed = MarketTimeframe.fromLabel(timeframe) ?: return 0
        val interval = parsed.seconds * 1_000L
        val intraday = parsed.isIntraday
        val gapThreshold = when {
            intraday -> interval + interval / 2L
            timeframe == "1D" -> 5L * 86_400_000L
            timeframe == "1W" || timeframe == "2W" || timeframe == "4W" ->
                21L * 86_400_000L
            else -> 60L * 86_400_000L
        }
        return candles.zipWithNext().count { (previous, next) ->
            val gap = next.timestamp - previous.timestamp
            gap > gapThreshold && (!intraday || gap <= 3L * 3_600_000L)
        }
    }

    fun aggregate(
        source: List<MarketCandle>,
        targetLabel: String,
        exchange: String
    ): List<MarketCandle> {
        if (source.isEmpty()) return emptyList()
        val target = MarketTimeframe.fromLabel(targetLabel) ?: return emptyList()
        val groups = linkedMapOf<Long, MutableList<MarketCandle>>()

        source.sortedBy { it.timestamp }.forEach { candle ->
            val bucket = TimeframeBuckets.bucketStart(
                timestamp = candle.timestamp,
                timeframe = target,
                exchange = exchange
            )
            groups.getOrPut(bucket) { ArrayList() }.add(candle)
        }

        return groups.map { (bucket, candles) ->
            MarketCandle(
                timestamp = bucket,
                open = candles.first().open,
                high = candles.maxOf { it.high },
                low = candles.minOf { it.low },
                close = candles.last().close,
                volume = candles.sumOf { it.volume }
            )
        }.sortedBy { it.timestamp }
    }

    fun isClosed(
        timestamp: Long,
        timeframe: String,
        exchange: String,
        now: Long
    ): Boolean {
        val parsed = MarketTimeframe.fromLabel(timeframe) ?: return false
        val bucketStart = TimeframeBuckets.bucketStart(
            timestamp = timestamp,
            timeframe = parsed,
            exchange = exchange
        )
        return if (parsed.isIntraday) {
            bucketStart + parsed.seconds * 1_000L <= now
        } else {
            val currentBucket = TimeframeBuckets.bucketStart(
                timestamp = now,
                timeframe = parsed,
                exchange = exchange
            )
            bucketStart < currentBucket
        }
    }

    fun isIntraday(timeframe: String): Boolean =
        MarketTimeframe.fromLabel(timeframe)?.isIntraday == true

    fun normalizedTimeframe(value: String): String =
        value.trim().replace(" ", "").uppercase(Locale.ROOT)
}