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
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
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

    private const val SEED_LIMIT = 5_000
    private const val STATUS_INTERVAL_MS = 1_000L

    private val tracked =
        ConcurrentHashMap<String, InstrumentKey>()

    private val running = AtomicBoolean(false)
    private val processedTicks = AtomicLong(0L)
    private val lastStatusAt = AtomicLong(0L)

    @Volatile
    private var lastTickAt = 0L

    @Volatile
    private var lastError: String? = null

    private val executorLock = Any()

    @Volatile
    private var executorRef: ExecutorService? = null

    private val executor: ExecutorService
        get() = synchronized(executorLock) {
            executorRef ?: Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "market-runtime-persistence").apply {
                    isDaemon = true
                }
            }.also { executorRef = it }
        }

    private val _status = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = _status.asStateFlow()

    private val candleResultListener:
        (MarketTick, CandleEngine.TickResult) -> Unit = { tick, result ->
            handleTick(tick, result)
        }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        MarketDataManager.start()
        MarketDataManager.addCandleResultListener(candleResultListener)
        publishStatus(force = true)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        MarketDataManager.removeCandleResultListener(candleResultListener)
        publishStatus(force = true)
    }

    fun shutdown() {
        stop()
        val current = synchronized(executorLock) {
            val existing = executorRef
            executorRef = null
            existing
        } ?: return

        current.shutdown()
        try {
            if (!current.awaitTermination(10, TimeUnit.SECONDS)) {
                current.shutdownNow()
            }
        } catch (_: InterruptedException) {
            current.shutdownNow()
            Thread.currentThread().interrupt()
        }
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

        publishStatus(force = true)
    }

    fun isTracked(key: InstrumentKey): Boolean {
        return tracked.containsKey(normalize(key).key())
    }

    fun trackedInstruments(): List<InstrumentKey> {
        return ArrayList(tracked.values)
    }

    fun seed(key: InstrumentKey, baseCandles: List<MarketCandle>) {
        seed(key, MarketTimeframe.MIN_1, baseCandles)
    }

    fun seed(
        key: InstrumentKey,
        timeframe: MarketTimeframe,
        candles: List<MarketCandle>
    ) {
        if (candles.isEmpty()) return
        MarketDataManager.seedCandles(normalize(key), candles, timeframe)
    }

    fun candles(
        key: InstrumentKey,
        timeframe: MarketTimeframe
    ): List<MarketCandle> {
        return MarketDataManager.getCandles(normalize(key), timeframe)
    }

    fun latestCandle(
        key: InstrumentKey,
        timeframe: MarketTimeframe
    ): MarketCandle? {
        return MarketDataManager.latestCandle(normalize(key), timeframe)
    }

    fun clear() {
        MarketDataManager.clearCandles()
        processedTicks.set(0L)
        lastTickAt = 0L
        lastError = null
        publishStatus(force = true)
    }

    private fun handleTick(
        tick: MarketTick,
        result: CandleEngine.TickResult
    ) {
        if (!running.get()) return
        if (!result.accepted) return

        val key = normalize(tick.key)
        val id = key.key()

        // The foreground runtime observes every instrument that the broker
        // feed actually delivers. This keeps candle persistence independent
        // of whether a chart screen/ViewModel is currently open.
        if (tracked.putIfAbsent(id, key) == null) {
            seedFromStore(key)
        }

        processedTicks.incrementAndGet()
        lastTickAt = tick.receivedAt.takeIf { it > 0L }
            ?: System.currentTimeMillis()

        enqueuePersistence(key = key, result = result)

        publishStatus(force = false)
    }

    private fun enqueuePersistence(
        key: InstrumentKey,
        result: CandleEngine.TickResult
    ) {
        if (result.currentCandles.isEmpty()) return

        val finalized = result.finalizedCandles.mapValues { (_, candles) ->
            ArrayList(candles)
        }
        val live = result.currentCandles.mapValues { (_, candle) ->
            listOf(candle)
        }

        try {
            executor.execute {
                try {
                    finalized.forEach { (timeframe, candles) ->
                        if (candles.isEmpty()) return@forEach

                        val saved = saveCandlesWithRetry(
                            key = key,
                            timeframe = timeframe,
                            candles = candles,
                            isComplete = true
                        )

                        check(saved == candles.size) {
                            "Candle persistence incomplete for $timeframe: " +
                                "saved $saved of ${candles.size}"
                        }
                    }

                    live.forEach { (timeframe, candles) ->
                        val saved = saveCandlesWithRetry(
                            key = key,
                            timeframe = timeframe,
                            candles = candles,
                            isComplete = false
                        )

                        check(saved == candles.size) {
                            "Live candle persistence incomplete for " +
                                "$timeframe: saved $saved of ${candles.size}"
                        }
                    }

                    SignalScanCoordinator.scan(key)
                } catch (e: Exception) {
                    recordError(e)
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun saveCandlesWithRetry(
        key: InstrumentKey,
        timeframe: String,
        candles: List<MarketCandle>,
        isComplete: Boolean
    ): Int {
        if (candles.isEmpty()) return 0

        val delaysMs = longArrayOf(250L, 500L, 1_000L, 2_000L)
        var lastSaved = 0

        for (attempt in 0..delaysMs.size) {
            // Finish already-enqueued persistence even if the feed is stopping.
            lastSaved = MarketFeedRepository.saveCandles(
                key = key,
                timeframe = timeframe,
                candles = candles,
                isComplete = isComplete
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
        try {
            executor.execute {
                try {
                    MarketTimeframe.values().forEach { timeframe ->
                        val stored = MarketFeedRepository.loadStoredCandles(
                            key = key,
                            timeframe = timeframe.label,
                            limit = SEED_LIMIT
                        )

                        if (stored.isNotEmpty()) {
                            MarketDataManager.seedCandles(
                                key = key,
                                candles = stored,
                                timeframe = timeframe
                            )
                        }
                    }
                } catch (e: Exception) {
                    recordError(e)
                }
            }
        } catch (_: RejectedExecutionException) {
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
