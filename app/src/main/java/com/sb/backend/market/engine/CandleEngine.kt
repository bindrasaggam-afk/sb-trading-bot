
package com.sb.backend.market.engine

import com.sb.backend.market.TimeframeBuckets
import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.market.model.MarketCandle
import com.sb.backend.market.model.MarketTick
import com.sb.backend.market.model.MarketTimeframe
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Universal tick-to-candle engine.
 *
 * Maintains one canonical 1-minute base series per instrument.
 * Higher timeframes are derived from the canonical base series.
 *
 * Thread safety:
 * All public state-mutating and state-reading methods are synchronized.
 *
 * Volume:
 * - Cumulative exchange volume is converted into deltas.
 * - First observed cumulative volume establishes a baseline.
 * - Out-of-order events never move the volume baseline backwards.
 * - When cumulative volume is unavailable, lastTradeQty is used.
 *
 * Candle alignment:
 * All bucket calculations are delegated to TimeframeBuckets.
 */
class CandleEngine {

    data class TickResult(
        val accepted: Boolean,
        val eventTime: Long = 0L,
        val baseTimestamp: Long = 0L,
        val currentBase: MarketCandle? = null,
        val finalizedBase: List<MarketCandle> = emptyList()
    )

    private class Series {
        val base = ArrayList<MarketCandle>()

        val firstTick = HashMap<Long, Long>()
        val lastTick = HashMap<Long, Long>()

        val processed = LinkedHashSet<String>()

        var previousVolume = -1L
        var previousVolumeTime = 0L
        var previousVolumeSession = ""

        var lastClosedBucket = 0L
        var openBucket = 0L
    }

    private val seriesByInstrument = HashMap<String, Series>()

    // ------------------------------------------------------------
    // LIVE TICK
    // ------------------------------------------------------------

    @Synchronized
    fun addTick(tick: MarketTick): TickResult {

        val normalizedKey = normalizeKey(tick.key)
        if (normalizedKey.isEmpty()) {
            return TickResult(false)
        }

        val ltp = tick.ltp

        if (!ltp.isFinite() || ltp <= 0.0) {
            return TickResult(false)
        }

        if (tick.volume < 0L || tick.lastTradeQty < 0L) {
            return TickResult(false)
        }

        val eventTime = eventTimeMillis(tick)

        if (eventTime <= 0L) {
            return TickResult(false)
        }

        val bucket = TimeframeBuckets.bucketStart(
            eventTime,
            MarketTimeframe.MIN_1,
            tick.key.exchange
        )

        if (bucket <= 0L) {
            return TickResult(false)
        }

        val series = seriesFor(normalizedKey)

        // Never allow an older bucket to modify a newer live candle.
        if (series.openBucket > 0L && bucket < series.openBucket) {
            return TickResult(false)
        }

        if (bucket < series.lastClosedBucket) {
            return TickResult(false)
        }

        val signature = signature(tick, eventTime)

        if (!series.processed.add(signature)) {
            return TickResult(false)
        }

        trimProcessed(series)

        val session = TimeframeBuckets.sessionKey(eventTime)

        val delta = calculateVolumeDelta(
            tick = tick,
            eventTime = eventTime,
            session = session,
            series = series
        )

        // Finalize older candles before opening the new bucket.
        val finalized = finalizeBefore(series, bucket)

        series.openBucket = bucket

        val index = findIndex(series.base, bucket)

        val current: MarketCandle

        if (index < 0) {

            current = MarketCandle(
                timestamp = bucket,
                open = ltp,
                high = ltp,
                low = ltp,
                close = ltp,
                volume = delta
            )

            insertSorted(series.base, current)

            series.firstTick[bucket] = eventTime
            series.lastTick[bucket] = eventTime

        } else {

            val existing = series.base[index]

            val firstTime = series.firstTick[bucket] ?: eventTime
            val lastTime = series.lastTick[bucket] ?: eventTime

            current = existing.copy(
                open = if (eventTime < firstTime) {
                    ltp
                } else {
                    existing.open
                },

                high = max(existing.high, ltp),

                low = min(existing.low, ltp),

                close = if (eventTime >= lastTime) {
                    ltp
                } else {
                    existing.close
                },

                volume = safeAdd(existing.volume, delta)
            )

            series.base[index] = current

            if (eventTime < firstTime) {
                series.firstTick[bucket] = eventTime
            }

            if (eventTime >= lastTime) {
                series.lastTick[bucket] = eventTime
            }
        }

        trimBase(series)

        return TickResult(
            accepted = true,
            eventTime = eventTime,
            baseTimestamp = bucket,
            currentBase = current,
            finalizedBase = finalized
        )
    }

    // ------------------------------------------------------------
    // VOLUME
    // ------------------------------------------------------------

    private fun calculateVolumeDelta(
        tick: MarketTick,
        eventTime: Long,
        session: String,
        series: Series
    ): Long {

        val cumulativeVolume = tick.volume

        // No cumulative volume available.
        if (cumulativeVolume <= 0L) {
            return tick.lastTradeQty.coerceAtLeast(0L)
        }

        // First observed cumulative volume establishes baseline.
        if (series.previousVolumeTime == 0L) {

            updateVolumeBaseline(
                cumulativeVolume,
                eventTime,
                session,
                series
            )

            return 0L
        }

        // Out-of-order ticks must not modify volume baseline.
        if (eventTime < series.previousVolumeTime) {
            return 0L
        }

        val delta = when {
            session != series.previousVolumeSession -> {
                // New exchange session.
                cumulativeVolume
            }

            cumulativeVolume < series.previousVolume -> {
                // Exchange volume counter reset.
                cumulativeVolume
            }

            else -> {
                cumulativeVolume - series.previousVolume
            }
        }

        updateVolumeBaseline(
            cumulativeVolume,
            eventTime,
            session,
            series
        )

        return delta.coerceAtLeast(0L)
    }

    private fun updateVolumeBaseline(
        volume: Long,
        eventTime: Long,
        session: String,
        series: Series
    ) {
        series.previousVolume = volume
        series.previousVolumeTime = eventTime
        series.previousVolumeSession = session
    }

    // ------------------------------------------------------------
    // HISTORICAL BASE CANDLES
    // ------------------------------------------------------------

    @Synchronized
    fun seedBase(
        key: InstrumentKey,
        candles: List<MarketCandle>
    ) {

        if (candles.isEmpty()) return

        val normalizedKey = normalizeKey(key)

        if (normalizedKey.isEmpty()) return

        val series = seriesFor(normalizedKey)

        val merged = LinkedHashMap<Long, MarketCandle>()

        // Existing in-memory candles have priority over stored copies.
        series.base.forEach { candle ->
            merged[candle.timestamp] = candle
        }

        val now = System.currentTimeMillis()

        candles.forEach { candle ->

            val normalized = normalizeCandle(candle)
                ?: return@forEach

            if (!isPlausibleStoredTimestamp(normalized.timestamp, now)) {
                return@forEach
            }

            if (!merged.containsKey(normalized.timestamp)) {
                merged[normalized.timestamp] = normalized
            }
        }

        series.base.clear()

        series.base.addAll(
            merged.values.sortedBy { it.timestamp }
        )

        trimBase(series)

        val newest = series.base.lastOrNull()?.timestamp ?: 0L

        if (newest > 1L) {

            val closedBefore = newest - 1L

            if (closedBefore > series.lastClosedBucket) {
                series.lastClosedBucket = closedBefore
            }

            // Do not replace an already active live bucket.
            if (series.openBucket == 0L) {
                series.openBucket = newest
            }
        }
    }

    // ------------------------------------------------------------
    // CANDLE ACCESS
    // ------------------------------------------------------------

    @Synchronized
    fun candles(
        key: InstrumentKey,
        timeframe: MarketTimeframe
    ): List<MarketCandle> {

        val series = seriesById(key) ?: return emptyList()

        if (timeframe == MarketTimeframe.MIN_1) {
            return ArrayList(series.base)
        }

        return aggregate(
            series = series,
            exchange = key.exchange,
            timeframe = timeframe
        )
    }

    // ------------------------------------------------------------
    // CANDLE AT TIMESTAMP
    // ------------------------------------------------------------

    @Synchronized
    fun candleAt(
        key: InstrumentKey,
        timeframe: MarketTimeframe,
        timestamp: Long
    ): MarketCandle? {

        val series = seriesById(key) ?: return null

        if (timestamp <= 0L) return null

        val bucket = TimeframeBuckets.bucketStart(
            timestamp,
            timeframe,
            key.exchange
        )

        if (bucket <= 0L) return null

        if (timeframe == MarketTimeframe.MIN_1) {

            val index = findIndex(series.base, bucket)

            return if (index < 0) {
                null
            } else {
                series.base[index]
            }
        }

        var open = 0.0
        var high = 0.0
        var low = 0.0
        var close = 0.0
        var volume = 0L

        var found = false

        for (candle in series.base) {

            val candleBucket = TimeframeBuckets.bucketStart(
                candle.timestamp,
                timeframe,
                key.exchange
            )

            if (candleBucket < bucket) continue

            if (candleBucket > bucket) break

            if (!found) {

                open = candle.open
                high = candle.high
                low = candle.low
                close = candle.close
                volume = candle.volume

                found = true

            } else {

                high = max(high, candle.high)
                low = min(low, candle.low)
                close = candle.close

                volume = safeAdd(volume, candle.volume)
            }
        }

        if (!found) return null

        return MarketCandle.create(
            timestamp = bucket,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume
        )
    }

    // ------------------------------------------------------------
    // RESET
    // ------------------------------------------------------------

    @Synchronized
    fun reset(key: InstrumentKey) {
        seriesByInstrument.remove(normalizeKey(key))
    }

    @Synchronized
    fun clear() {
        seriesByInstrument.clear()
    }

    // ------------------------------------------------------------
    // BASE CANDLE COUNT
    // ------------------------------------------------------------

    @Synchronized
    fun baseCandleCount(key: InstrumentKey): Int {
        return seriesById(key)?.base?.size ?: 0
    }

    // ------------------------------------------------------------
    // TIMEFRAME AGGREGATION
    // ------------------------------------------------------------

    private fun aggregate(
        series: Series,
        exchange: String,
        timeframe: MarketTimeframe
    ): List<MarketCandle> {

        if (series.base.isEmpty()) {
            return emptyList()
        }

        val result = ArrayList<MarketCandle>()

        for (candle in series.base) {

            val bucket = TimeframeBuckets.bucketStart(
                candle.timestamp,
                timeframe,
                exchange
            )

            if (bucket <= 0L) continue

            val last = result.lastOrNull()

            if (last == null || last.timestamp != bucket) {

                result.add(
                    MarketCandle(
                        timestamp = bucket,
                        open = candle.open,
                        high = candle.high,
                        low = candle.low,
                        close = candle.close,
                        volume = candle.volume
                    )
                )

            } else {

                result[result.lastIndex] = last.copy(
                    high = max(last.high, candle.high),
                    low = min(last.low, candle.low),
                    close = candle.close,
                    volume = safeAdd(last.volume, candle.volume)
                )
            }
        }

        return if (result.size > MAX_CANDLES) {
            ArrayList(
                result.subList(
                    result.size - MAX_CANDLES,
                    result.size
                )
            )
        } else {
            result
        }
    }

    // ------------------------------------------------------------
    // FINALIZATION
    // ------------------------------------------------------------

    private fun finalizeBefore(
        series: Series,
        bucket: Long
    ): List<MarketCandle> {

        if (bucket <= series.lastClosedBucket) {
            return emptyList()
        }

        val finalized = ArrayList<MarketCandle>()

        var newestFinalized = series.lastClosedBucket

        for (candle in series.base) {

            if (candle.timestamp <= series.lastClosedBucket) {
                continue
            }

            if (candle.timestamp >= bucket) {
                break
            }

            finalized.add(candle)

            newestFinalized = candle.timestamp
        }

        if (newestFinalized > series.lastClosedBucket) {
            series.lastClosedBucket = newestFinalized
        }

        return finalized
    }

    // ------------------------------------------------------------
    // EVENT TIME
    // ------------------------------------------------------------

    private fun eventTimeMillis(tick: MarketTick): Long {

        val received = TimeframeBuckets.normalizeTimestamp(
            tick.receivedAt.takeIf { it > 0L }
                ?: System.currentTimeMillis()
        )

        if (received <= 0L) {
            return 0L
        }

        val candidates = listOfNotNull(
            tick.lastTradeTime,
            tick.exchangeTime
        )

        for (raw in candidates) {

            val normalized = TimeframeBuckets.normalizeTimestamp(raw)

            if (isPlausibleEventTimestamp(normalized, received)) {
                return normalized
            }
        }

        // Fall back to local receive time for malformed broker timestamps.
        return received
    }

    private fun isPlausibleEventTimestamp(
        eventTime: Long,
        reference: Long
    ): Boolean {

        if (eventTime <= 0L || reference <= 0L) {
            return false
        }

        val latestAllowed = safeAdd(
            reference,
            MAX_FUTURE_SKEW_MILLIS
        )

        val earliestAllowed =
            (reference - MAX_STALE_PAST_MILLIS).coerceAtLeast(1L)

        return eventTime in earliestAllowed..latestAllowed
    }

    private fun isPlausibleStoredTimestamp(
        timestamp: Long,
        now: Long
    ): Boolean {

        if (timestamp <= 0L) return false

        if (now <= 0L) return true

        return timestamp <= safeAdd(
            now,
            MAX_FUTURE_SKEW_MILLIS
        )
    }

    // ------------------------------------------------------------
    // DUPLICATE TICK PROTECTION
    // ------------------------------------------------------------

    private fun signature(
        tick: MarketTick,
        eventTime: Long
    ): String {

        return normalizeKey(tick.key) + "|" +
            eventTime + "|" +
            java.lang.Double.doubleToLongBits(tick.ltp) + "|" +
            tick.volume + "|" +
            tick.lastTradeQty
    }

    private fun trimProcessed(series: Series) {

        while (series.processed.size > MAX_REMEMBERED_TICKS) {

            val iterator = series.processed.iterator()

            if (!iterator.hasNext()) break

            iterator.next()
            iterator.remove()
        }
    }

    // ------------------------------------------------------------
    // CANDLE VALIDATION
    // ------------------------------------------------------------

    private fun normalizeCandle(
        candle: MarketCandle
    ): MarketCandle? {

        val timestamp = TimeframeBuckets.normalizeTimestamp(
            candle.timestamp
        )

        if (!MarketCandle.isValid(
                timestamp = timestamp,
                open = candle.open,
                high = candle.high,
                low = candle.low,
                close = candle.close,
                volume = candle.volume
            )
        ) {
            return null
        }

        return candle.copy(timestamp = timestamp)
    }

    // ------------------------------------------------------------
    // SERIES MANAGEMENT
    // ------------------------------------------------------------

    private fun seriesFor(normalizedKey: String): Series {
        return seriesByInstrument.getOrPut(normalizedKey) {
            Series()
        }
    }

    private fun seriesById(key: InstrumentKey): Series? {
        return seriesByInstrument[normalizeKey(key)]
    }

    private fun normalizeKey(key: InstrumentKey): String {

        val exchange = key.exchange.trim().uppercase(Locale.ROOT)
        val symbol = key.symbol.trim().uppercase(Locale.ROOT)

        if (exchange.isEmpty() || symbol.isEmpty()) {
            return ""
        }

        return "$exchange|$symbol"
    }

    // ------------------------------------------------------------
    // SORTED CANDLE HELPERS
    // ------------------------------------------------------------

    private fun findIndex(
        candles: List<MarketCandle>,
        timestamp: Long
    ): Int {

        for (index in candles.indices.reversed()) {

            val current = candles[index].timestamp

            if (current == timestamp) {
                return index
            }

            if (current < timestamp) {
                return -1
            }
        }

        return -1
    }

    private fun insertSorted(
        candles: MutableList<MarketCandle>,
        candle: MarketCandle
    ) {

        var insertAt = candles.size

        for (index in candles.indices) {

            if (candles[index].timestamp > candle.timestamp) {
                insertAt = index
                break
            }
        }

        candles.add(insertAt, candle)
    }

    // ------------------------------------------------------------
    // MEMORY LIMITS
    // ------------------------------------------------------------

    private fun trimBase(series: Series) {

        while (series.base.size > MAX_BASE_CANDLES) {

            val removed = series.base.removeAt(0)

            series.firstTick.remove(removed.timestamp)
            series.lastTick.remove(removed.timestamp)
        }
    }

    // ------------------------------------------------------------
    // SAFE ARITHMETIC
    // ------------------------------------------------------------

    private fun safeAdd(
        first: Long,
        second: Long
    ): Long {

        if (second > 0L && first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE
        }

        if (second < 0L && first < Long.MIN_VALUE - second) {
            return Long.MIN_VALUE
        }

        return (first + second).coerceAtLeast(0L)
    }

    private companion object {

        private const val MAX_BASE_CANDLES = 12_000
        private const val MAX_CANDLES = 2_000
        private const val MAX_REMEMBERED_TICKS = 5_000

        private const val MAX_FUTURE_SKEW_MILLIS =
            10L * 60L * 1_000L

        private const val MAX_STALE_PAST_MILLIS =
            7L * 24L * 60L * 60L * 1_000L
    }
}
