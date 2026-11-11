package com.sb.backend.services

import com.sb.backend.market.MarketDataManager
import com.sb.backend.market.engine.CandleEngine
import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.market.model.MarketCandle
import com.sb.backend.market.model.MarketTick
import com.sb.backend.market.model.MarketTimeframe
import com.sb.backend.market.repo.MarketFeedRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object MarketRuntimeService {

    enum class RuntimeState {
        STOPPED,
        RUNNING
    }

    data class RuntimeStatus(
        val state: RuntimeState = RuntimeState.STOPPED,
        val trackedInstruments: Int = 0,
        val processedTicks: Long = 0L,
        val lastTickAt: Long = 0L,
        val lastError: String? = null
    )

    private const val BASE_TIMEFRAME_LABEL = "1m"
    private const val SEED_LIMIT = 5_000
    private const val STATUS_INTERVAL_MS = 1_000L

    private val engine = CandleEngine()

    private val tracked =
        ConcurrentHashMap<String, InstrumentKey>()

    private val lastSavedTarget =
        ConcurrentHashMap<String, Long>()

    private val targetTimeframes = listOf(
        MarketTimeframe.MIN_5,
        MarketTimeframe.MIN_15,
        MarketTimeframe.HOUR_1
    )

    private val running = AtomicBoolean(false)
    private val processedTicks = AtomicLong(0L)
    private val lastStatusAt = AtomicLong(0L)

    @Volatile
    private var lastTickAt = 0L

    @Volatile
    private var lastError: String? = null

    private val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor()
    }

    private val _status = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = _status.asStateFlow()

    private val tickListener: (MarketTick) -> Unit = { tick ->
        handleTick(tick)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        MarketDataManager.addTickListener(tickListener)
        publishStatus(force = true)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        MarketDataManager.removeTickListener(tickListener)
        publishStatus(force = true)
    }

    fun isRunning(): Boolean = running.get()

    fun subscribe(exchange: String, symbol: String) {
        if (exchange.isBlank() || symbol.isBlank()) return
        subscribe(InstrumentKey(exchange = exchange, symbol = symbol))
    }

    fun subscribe(key: InstrumentKey) {
        val normalized = normalize(key)
        val id = normalized.key()

        if (tracked.putIfAbsent(id, normalized) != null) return

        publishStatus(force = true)

        try {
            MarketFeedRepository.requestSubscription(normalized)
        } catch (e: Exception) {
            recordError(e)
        }

        seedFromStore(normalized)
    }

    fun unsubscribe(key: InstrumentKey) {
        val normalized = normalize(key)
        val id = normalized.key()

        if (tracked.remove(id) == null) return

        engine.reset(normalized)
        val savedKeys = lastSavedTarget.keys.iterator()
        while (savedKeys.hasNext()) {
            if (savedKeys.next().startsWith("$id|")) {
                savedKeys.remove()
            }
        }

        publishStatus(force = true)
    }

    fun isTracked(key: InstrumentKey): Boolean {
        return tracked.containsKey(normalize(key).key())
    }

    fun trackedInstruments(): List<InstrumentKey> {
        return ArrayList(tracked.values)
    }

    fun seed(key: InstrumentKey, baseCandles: List<MarketCandle>) {
        if (baseCandles.isEmpty()) return
        engine.seedBase(normalize(key), baseCandles)
    }

    fun candles(
        key: InstrumentKey,
        timeframe: MarketTimeframe
    ): List<MarketCandle> {
        return engine.candles(normalize(key), timeframe)
    }

    fun latestCandle(
        key: InstrumentKey,
        timeframe: MarketTimeframe
    ): MarketCandle? {
        return engine.candles(normalize(key), timeframe).lastOrNull()
    }

    fun clear() {
        engine.clear()
        lastSavedTarget.clear()
        processedTicks.set(0L)
        lastTickAt = 0L
        lastError = null
        publishStatus(force = true)
    }

    private fun handleTick(tick: MarketTick) {
        if (!running.get()) return
        if (!tick.isValid) return

        val key = normalize(tick.key)
        val id = key.key()

        // The foreground runtime observes every instrument that the broker
        // feed actually delivers. This keeps candle persistence independent
        // of whether a chart screen/ViewModel is currently open.
        if (tracked.putIfAbsent(id, key) == null) {
            seedFromStore(key)
        }

        val result = try {
            engine.addTick(tick)
        } catch (e: Exception) {
            recordError(e)
            return
        }

        if (!result.accepted) return

        processedTicks.incrementAndGet()
        lastTickAt = tick.receivedAt.takeIf { it > 0L }
            ?: System.currentTimeMillis()

        if (result.finalizedBase.isNotEmpty()) {
            enqueuePersistence(
                key = key,
                finalizedBase = result.finalizedBase,
                latestBaseTimestamp = result.baseTimestamp
            )
        }

        publishStatus(force = false)
    }

    private fun enqueuePersistence(
        key: InstrumentKey,
        finalizedBase: List<MarketCandle>,
        latestBaseTimestamp: Long
    ) {
        if (finalizedBase.isEmpty()) return

        val snapshot = ArrayList(finalizedBase)

        executor.execute {
            try {
                val baseSaved = saveCandlesWithRetry(
                    key = key,
                    timeframe = BASE_TIMEFRAME_LABEL,
                    candles = snapshot
                )
                check(baseSaved == snapshot.size) {
                    "Base candle persistence incomplete: saved " +
                        baseSaved + " of " + snapshot.size
                }

                persistClosedTargets(key, latestBaseTimestamp)

                SignalScanCoordinator.scan(key)
            } catch (e: Exception) {
                recordError(e)
            }
        }
    }

    private fun persistClosedTargets(
        key: InstrumentKey,
        latestBaseTimestamp: Long
    ) {
        if (latestBaseTimestamp <= 0L) return

        val id = key.key()

        targetTimeframes.forEach { timeframe ->
            val savedKey = "$id|${timeframe.label}"
            val lastSaved = lastSavedTarget[savedKey] ?: 0L
            val interval = timeframe.seconds * 1_000L

            val closed = engine.candles(key, timeframe)
                .filter { candle ->
                    candle.timestamp > lastSaved &&
                        candle.timestamp + interval <= latestBaseTimestamp
                }

            if (closed.isEmpty()) return@forEach

            val saved = saveCandlesWithRetry(
                key = key,
                timeframe = timeframe.label,
                candles = closed
            )

            if (saved == closed.size) {
                lastSavedTarget[savedKey] = closed.last().timestamp
            }
        }
    }

    /**
     * A candle batch is persisted only when every row is saved.
     * Retry transient failures and never advance the timeframe cursor
     * after a partial or failed write.
     */
    private fun saveCandlesWithRetry(
        key: InstrumentKey,
        timeframe: String,
        candles: List<MarketCandle>
    ): Int {
        if (candles.isEmpty()) return 0

        val delaysMs = longArrayOf(250L, 500L, 1_000L, 2_000L)
        var lastSaved = 0

        for (attempt in 0..delaysMs.size) {
            // Finish already-enqueued persistence even if the feed is stopping.
            lastSaved = MarketFeedRepository.saveCandles(
                key = key,
                timeframe = timeframe,
                candles = candles
            )

            if (lastSaved == candles.size) return lastSaved

            if (attempt < delaysMs.size) {
                try {
                    Thread.sleep(delaysMs[attempt])
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return 0
                }
            }
        }

        throw IllegalStateException(
            "Candle persistence incomplete for " +
                key.exchange + ":" + key.symbol + "/" + timeframe +
                ": saved " + lastSaved + " of " + candles.size +
                " after " + (delaysMs.size + 1) + " attempts"
        )
    }
    private fun seedFromStore(key: InstrumentKey) {
        executor.execute {
            try {
                val stored = MarketFeedRepository.loadStoredCandles(
                    key = key,
                    timeframe = BASE_TIMEFRAME_LABEL,
                    limit = SEED_LIMIT
                )

                if (stored.isNotEmpty()) {
                    engine.seedBase(key, stored)
                }
            } catch (e: Exception) {
                recordError(e)
            }
        }
    }

    private fun publishStatus(force: Boolean) {
        val now = System.currentTimeMillis()

        if (!force) {
            val previous = lastStatusAt.get()
            if (now - previous < STATUS_INTERVAL_MS) return
            if (!lastStatusAt.compareAndSet(previous, now)) return
        } else {
            lastStatusAt.set(now)
        }

        _status.value = RuntimeStatus(
            state = if (running.get()) {
                RuntimeState.RUNNING
            } else {
                RuntimeState.STOPPED
            },
            trackedInstruments = tracked.size,
            processedTicks = processedTicks.get(),
            lastTickAt = lastTickAt,
            lastError = lastError
        )
    }

    private fun recordError(error: Exception) {
        lastError = error.message ?: "Market runtime error"
        publishStatus(force = true)
    }

    private fun normalize(key: InstrumentKey): InstrumentKey {
        return InstrumentKey(
            exchange = key.exchange.trim().uppercase(Locale.ROOT),
            symbol = key.symbol.trim()
        )
    }
}
