package com.sb.backend.market.engine

import com.sb.backend.market.ExchangeSessionCalendar
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
 * Every supported timeframe owns an independent candle series. Each accepted
 * raw tick is applied directly to every series using that timeframe's own
 * bucket boundary. Higher timeframe candles are never derived from another
 * timeframe's candles.
 *
 * Thread safety:
 * All public state-mutating and state-reading methods are synchronized.
 *
 * Volume:
 * - Cumulative exchange volume is converted into deltas once per raw tick.
 * - The same delta is applied to every timeframe series.
 * - First observed cumulative volume establishes a baseline.
 * - Out-of-order events never move the volume baseline backwards.
 * - When cumulative volume is unavailable, lastTradeQty is used.
 *
 * Candle alignment:
 * All bucket calculations are delegated to TimeframeBuckets.
 */
class CandleEngine(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    data class TickResult(
        val accepted: Boolean,
        val eventTime: Long = 0L,
        val baseTimestamp: Long = 0L,
        val currentBase: MarketCandle? = null,
        val finalizedBase: List<MarketCandle> = emptyList(),
        val currentCandles: Map<String, MarketCandle> = emptyMap(),
        val finalizedCandles: Map<String, List<MarketCandle>> = emptyMap()
    ) {
        fun current(timeframe: MarketTimeframe): MarketCandle? =
            currentCandles[timeframe.label]

        fun finalized(timeframe: MarketTimeframe): List<MarketCandle> =
            finalizedCandles[timeframe.label].orEmpty()
    }

    private class TimeframeSeries {
        val candles = ArrayList<MarketCandle>()
        val firstTick = HashMap<Long, Long>()
        val lastTick = HashMap<Long, Long>()

        var openBucket = 0L
        var lastClosedBucket = 0L
    }

    private class Series {
        val byTimeframe = HashMap<String, TimeframeSeries>()
        val processed = LinkedHashSet<String>()

        var previousVolume = -1L
        var previousVolumeTime = 0L
        var previousVolumeSession = ""

        init {
            byTimeframe[MarketTimeframe.MIN_1.label] = TimeframeSeries()
        }

        fun timeframe(label: String): TimeframeSeries {
            return byTimeframe.getOrPut(label) { TimeframeSeries() }
        }

        val base: TimeframeSeries
            get() = timeframe(MarketTimeframe.MIN_1.label)
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

        if (!ExchangeSessionCalendar.isTradingTime(eventTime, tick.key.exchange)) {
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

        if (series.base.openBucket > 0L && bucket < series.base.openBucket) {
            return TickResult(false)
        }

        if (bucket < series.base.lastClosedBucket) {
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

        val currentCandles = LinkedHashMap<String, MarketCandle>()
        val finalizedCandles = LinkedHashMap<String, List<MarketCandle>>()

        MarketTimeframe.values().forEach { timeframe ->
            val target = series.timeframe(timeframe.label)
            val targetBucket = TimeframeBuckets.bucketStart(
                eventTime,
                timeframe,
                tick.key.exchange
            )

            if (targetBucket <= 0L) return@forEach

            val finalized = finalizeBefore(target, targetBucket)
            target.openBucket = targetBucket

            val current = upsertCandle(
                candles = target,
                bucket = targetBucket,
                eventTime = eventTime,
                ltp = ltp,
                delta = delta
            )

            trimCandles(target)

            currentCandles[timeframe.label] = current
            finalizedCandles[timeframe.label] = finalized
        }

        val currentBase = currentCandles[MarketTimeframe.MIN_1.label]
            ?: return TickResult(false)

        return TickResult(
            accepted = true,
            eventTime = eventTime,
            baseTimestamp = bucket,
            currentBase = currentBase,
            finalizedBase = finalizedCandles[MarketTimeframe.MIN_1.label].orEmpty(),
            currentCandles = currentCandles,
            finalizedCandles = finalizedCandles
        )
    }

    private fun upsertCandle(
        candles: TimeframeSeries,
        bucket: Long,
        eventTime: Long,
        ltp: Double,
        delta: Long
    ): MarketCandle {
        val index = findIndex(candles.candles, bucket)

        if (index < 0) {
            val current = MarketCandle(
                timestamp = bucket,
                open = ltp,
                high = ltp,
                low = ltp,
                close = ltp,
                volume = delta
            )

            insertSorted(candles.candles, current)
            candles.firstTick[bucket] = eventTime
            candles.lastTick[bucket] = eventTime

            return current
        }

        val existing = candles.candles[index]
        val firstTime = candles.firstTick[bucket] ?: eventTime
        val lastTime = candles.lastTick[bucket] ?: eventTime

        val current = existing.copy(
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

        candles.candles[index] = current

        if (eventTime < firstTime) {
            candles.firstTick[bucket] = eventTime
        }

        if (eventTime >= lastTime) {
            candles.lastTick[bucket] = eventTime
        }

        return current
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

        if (cumulativeVolume <= 0L) {
            return tick.lastTradeQty.coerceAtLeast(0L)
        }

        if (series.previousVolumeTime == 0L) {
            updateVolumeBaseline(
                cumulativeVolume,
                eventTime,
                session,
                series
            )

            return 0L
        }

        if (eventTime < series.previousVolumeTime) {
            return 0L
        }

        val delta = when {
            session != series.previousVolumeSession -> {
                cumulativeVolume
            }

            cumulativeVolume < series.previousVolume -> {
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
    // HISTORICAL CANDLES
    // ------------------------------------------------------------

    @Synchronized
    fun seedBase(
        key: InstrumentKey,
        candles: List<MarketCandle>
    ) {
        seed(key, MarketTimeframe.MIN_1, candles)
    }

    @Synchronized
    fun seed(
        key: InstrumentKey,
        timeframe: MarketTimeframe,
        candles: List<MarketCandle>
    ) {
        if (candles.isEmpty()) return

        val normalizedKey = normalizeKey(key)

        if (normalizedKey.isEmpty()) return

        val series = seriesFor(normalizedKey)
        val target = series.timeframe(timeframe.label)
        val merged = LinkedHashMap<Long, MarketCandle>()

        target.candles.forEach { candle ->
            merged[candle.timestamp] = candle
        }

        val now = clock()

        candles.forEach { candle ->
            val bucket = TimeframeBuckets.bucketStart(
                candle.timestamp,
                timeframe,
                key.exchange
            )

            val normalized = normalizeCandle(candle.copy(timestamp = bucket))
                ?: return@forEach

            if (!isPlausibleStoredTimestamp(normalized.timestamp, now)) {
                return@forEach
            }

            if (!merged.containsKey(normalized.timestamp)) {
                merged[normalized.timestamp] = normalized
            }
        }

        target.candles.clear()
        target.candles.addAll(merged.values.sortedBy { it.timestamp })
        trimCandles(target)

        val newest = target.candles.lastOrNull()?.timestamp ?: 0L

        if (newest > 1L) {
            val closedBefore = newest - 1L

            if (closedBefore > target.lastClosedBucket) {
                target.lastClosedBucket = closedBefore
            }

            if (target.openBucket == 0L) {
                target.openBucket = newest
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
        return ArrayList(series.timeframe(timeframe.label).candles)
    }

    @Synchronized
    fun candleAt(
        key: InstrumentKey,
        timeframe: MarketTimeframe,
        timestamp: Long
    ): MarketCandle? {
        if (timestamp <= 0L) return null

        val series = seriesById(key) ?: return null

        val bucket = TimeframeBuckets.bucketStart(
            timestamp,
            timeframe,
            key.exchange
        )

        if (bucket <= 0L) return null

        val index = findIndex(series.timeframe(timeframe.label).candles, bucket)
        return if (index < 0) null else series.timeframe(timeframe.label).candles[index]
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
        return seriesById(key)?.base?.candles?.size ?: 0
    }

    // ------------------------------------------------------------
    // FINALIZATION
    // ------------------------------------------------------------

    private fun finalizeBefore(
        candles: TimeframeSeries,
        bucket: Long
    ): List<MarketCandle> {
        if (bucket <= candles.lastClosedBucket) {
            return emptyList()
        }

        val finalized = ArrayList<MarketCandle>()
        var newestFinalized = candles.lastClosedBucket

        for (candle in candles.candles) {
            if (candle.timestamp <= candles.lastClosedBucket) {
                continue
            }

            if (candle.timestamp >= bucket) {
                break
            }

            finalized.add(candle)
            newestFinalized = candle.timestamp
        }

        if (newestFinalized > candles.lastClosedBucket) {
            candles.lastClosedBucket = newestFinalized
        }

        return finalized
    }

    // ------------------------------------------------------------
    // EVENT TIME
    // ------------------------------------------------------------

    private fun eventTimeMillis(tick: MarketTick): Long {
        val received = TimeframeBuckets.normalizeTimestamp(
            tick.receivedAt.takeIf { it > 0L }
                ?: clock()
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

        return timestamp <= safeAdd(now, MAX_FUTURE_SKEW_MILLIS)
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
        val timestamp = TimeframeBuckets.normalizeTimestamp(candle.timestamp)

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
        return seriesByInstrument.getOrPut(normalizedKey) { Series() }
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

    private fun trimCandles(candles: TimeframeSeries) {
        while (candles.candles.size > MAX_CANDLES_PER_TIMEFRAME) {
            val removed = candles.candles.removeAt(0)
            candles.firstTick.remove(removed.timestamp)
            candles.lastTick.remove(removed.timestamp)
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
        private const val MAX_CANDLES_PER_TIMEFRAME = 12_000
        private const val MAX_REMEMBERED_TICKS = 5_000

        private const val MAX_FUTURE_SKEW_MILLIS =
            10L * 60L * 1_000L

        private const val MAX_STALE_PAST_MILLIS =
            7L * 24L * 60L * 60L * 1_000L
    }
}
