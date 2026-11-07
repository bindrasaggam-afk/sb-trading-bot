package com.sb.backend.services

import com.sb.backend.core.BackendManager
import com.sb.backend.market.TimeframeBuckets
import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.market.model.MarketTimeframe
import com.sb.backend.models.Candle
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Runs [SignalEngine] on candles that are already persisted.
 *
 * Missing higher timeframes are aggregated from the deepest stored
 * lower timeframe, so signals can be produced without hammering the
 * historical API. No synthetic candles are invented: if there is not
 * enough real data the scan simply produces nothing.
 */
object SignalScanCoordinator {

    private const val MIN_CANDLES = 200
    private const val SOURCE_LIMIT = 5_000
    private const val MIN_SCAN_INTERVAL_MS = 60_000L

    private val targets = listOf("5m", "15m", "1h")

    private val sources = mapOf(
        "5m" to listOf("1m"),
        "15m" to listOf("5m", "1m"),
        "1h" to listOf("15m", "5m", "1m")
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val lastScanAt = ConcurrentHashMap<String, Long>()
    private val inFlight = Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>()
    )

    fun scan(key: InstrumentKey, force: Boolean = false) {
        if (!BackendManager.isInitialized()) return
        if (key.symbol.isBlank() || key.exchange.isBlank()) return

        val id = key.key()
        val now = System.currentTimeMillis()
        val last = lastScanAt[id] ?: 0L
        if (!force && now - last < MIN_SCAN_INTERVAL_MS) return
        if (!inFlight.add(id)) return

        lastScanAt[id] = now
        executor.execute {
            try {
                if (ensureTargets(key, 0)) {
                    generate(key)
                }
            } catch (_: Exception) {
            } finally {
                inFlight.remove(id)
            }
        }
    }

    private fun ensureTargets(key: InstrumentKey, index: Int): Boolean {
        if (index >= targets.size) return true

        val target = targets[index]
        if (count(key, target) >= MIN_CANDLES) {
            return ensureTargets(key, index + 1)
        }
        if (!aggregate(key, target)) {
            return false
        }
        return ensureTargets(key, index + 1)
    }

    private fun count(key: InstrumentKey, timeframe: String): Int {
        return try {
            BackendManager.market().getCandles(
                symbol = key.symbol,
                timeframe = timeframe,
                exchange = key.exchange,
                limit = MIN_CANDLES
            ).size
        } catch (_: Exception) {
            0
        }
    }

    private fun aggregate(key: InstrumentKey, target: String): Boolean {
        val targetTimeframe =
            MarketTimeframe.fromLabel(target) ?: return false

        sources[target].orEmpty().forEach { source ->
            val sourceCandles = try {
                BackendManager.market().getCandles(
                    symbol = key.symbol,
                    timeframe = source,
                    exchange = key.exchange,
                    limit = SOURCE_LIMIT
                )
            } catch (_: Exception) {
                emptyList()
            }

            if (sourceCandles.size < MIN_CANDLES) return@forEach

            val aggregated = aggregateCandles(
                source = sourceCandles,
                targetTimeframe = targetTimeframe,
                targetLabel = target,
                exchange = key.exchange
            )

            if (aggregated.size >= MIN_CANDLES) {
                return try {
                    BackendManager.market().saveCandles(
                        symbol = key.symbol,
                        exchange = key.exchange,
                        timeframe = target,
                        candles = aggregated
                    )
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }

        return false
    }

    private fun aggregateCandles(
        source: List<Candle>,
        targetTimeframe: MarketTimeframe,
        targetLabel: String,
        exchange: String
    ): List<Candle> {
        val buckets = LinkedHashMap<Long, Candle>()

        source.forEach { candle ->
            val timestamp = TimeframeBuckets.normalizeTimestamp(candle.timestamp)
            if (timestamp <= 0L) return@forEach

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

    private fun generate(key: InstrumentKey) {
        try {
            BackendManager.signalEngine().generate(
                symbol = key.symbol,
                exchange = key.exchange,
                timeframe = "5m"
            )
        } catch (_: Exception) {
        }
    }
}
