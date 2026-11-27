package com.sb.backend.services

import com.sb.backend.market.MarketDataManager
import com.sb.backend.market.engine.CandleEngine
import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.market.model.MarketCandle
import com.sb.backend.market.model.MarketTick
import com.sb.backend.market.model.MarketTimeframe
import com.sb.backend.market.repo.MarketFeedRepository
import com.sb.backend.core.BackendManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object MarketRuntimeService {

    enum class RuntimeState {
        STOPPED,
        RUNNING
    }

    enum class CandlePersistenceMode {
        FAST,
        DURABLE
    }

    data class RuntimeStatus(
        val state: RuntimeState = RuntimeState.STOPPED,
        val trackedInstruments: Int = 0,
        val processedTicks: Long = 0L,
        val lastTickAt: Long = 0L,
        val lastError: String? = null,
        val persistenceMode: CandlePersistenceMode = CandlePersistenceMode.FAST,
        val savedCandles: Long = 0L,
        val failedWrites: Long = 0L,
        val queueDepth: Int = 0,
        val synchronousWrites: Long = 0L
    )

    private const val SEED_LIMIT = 5_000
    private const val STATUS_INTERVAL_MS = 1_000L

    private val tracked =
        ConcurrentHashMap<String, InstrumentKey>()

    private val running = AtomicBoolean(false)
    private val processedTicks = AtomicLong(0L)
    private val savedCandles = AtomicLong(0L)
    private val failedWrites = AtomicLong(0L)
    private val synchronousWrites = AtomicLong(0L)
    private val persistenceModeCode = AtomicLong(CandlePersistenceMode.FAST.ordinal.toLong())
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
            executorRef ?: ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue<Runnable>(2_048),
                { runnable ->
                    Thread(runnable, "market-runtime-persistence").apply {
                        isDaemon = true
                    }
                },
                ThreadPoolExecutor.AbortPolicy()
            ).also { executorRef = it }
        }

    fun currentPersistenceMode(): CandlePersistenceMode {
        val index = persistenceModeCode.get().toInt()
        return CandlePersistenceMode.values().getOrElse(index) {
            CandlePersistenceMode.FAST
        }
    }

    fun setPersistenceMode(mode: CandlePersistenceMode) {
        persistenceModeCode.set(mode.ordinal.toLong())
        publishStatus(force = true)
    }

    fun persistenceStatsText(): String {
        val queueSize = (executorRef as? ThreadPoolExecutor)?.queue?.size ?: 0
        return "Mode: " + currentPersistenceMode().name +
            "\nProcessed ticks: " + processedTicks.get() +
            "\nSaved candles: " + savedCandles.get() +
            "\nFailed writes: " + failedWrites.get() +
            "\nQueue depth: " + queueSize +
            "\nSynchronous writes: " + synchronousWrites.get()
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
        // Startup recovery: drain rows left durable by the prior process.
        try {
            executor.execute { drainPendingCandleQueue() }
        } catch (e: RejectedExecutionException) {
            failedWrites.incrementAndGet()
            recordError(e)
        }
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
        savedCandles.set(0L)
        failedWrites.set(0L)
        synchronousWrites.set(0L)
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

        // Enqueue on the dedicated executor only; tick callback does no DB work.
        try {
            executor.execute {
                try {
                    finalized.forEach { (timeframe, candles) ->
                        candles.forEach { candle ->
                            val queued = BackendManager.market().enqueueCandlePersistence(
                                key.symbol, key.exchange, timeframe,
                                com.sb.backend.models.Candle(
                                    key.symbol, timeframe, candle.timestamp,
                                    candle.open, candle.high, candle.low, candle.close,
                                    candle.volume, true, key.exchange
                                )
                            )
                            check(queued) { "Durable enqueue failed for finalized candle" }
                        }
                    }
                    live.forEach { (timeframe, candles) ->
                        candles.forEach { candle ->
                            val queued = BackendManager.market().enqueueCandlePersistence(
                                key.symbol, key.exchange, timeframe,
                                com.sb.backend.models.Candle(
                                    key.symbol, timeframe, candle.timestamp,
                                    candle.open, candle.high, candle.low, candle.close,
                                    candle.volume, false, key.exchange
                                )
                            )
                            check(queued) { "Durable enqueue failed for live candle" }
                        }
                    }
                    drainPendingCandleQueue()
                    SignalScanCoordinator.scan(key)
                } catch (e: Exception) {
                    failedWrites.incrementAndGet()
                    recordError(e)
                }
            }
        } catch (e: RejectedExecutionException) {
            // Saturation fallback: preserve candle data in the durable SQLite
            // queue rather than silently losing it. This rare path may block
            // the feed callback briefly on SQLite; normal traffic stays async.
            persistRejectedWorkToDurableQueue(key, finalized, live)
            failedWrites.incrementAndGet()
            recordError(e)
        }
    }

    private fun persistRejectedWorkToDurableQueue(
        key: InstrumentKey,
        finalized: Map<String, List<MarketCandle>>,
        live: Map<String, List<MarketCandle>>
    ) {
        if (!BackendManager.isInitialized()) {
            recordError(IllegalStateException("Persistence executor saturated and backend not initialized"))
            return
        }
        try {
            val repo = BackendManager.market()
            (finalized.mapValues { it.value to true } + live.mapValues { it.value to false })
                .forEach { (timeframe, pair) ->
                    pair.first.forEach { candle ->
                        val saved = repo.enqueueCandlePersistence(
                            key.symbol, key.exchange, timeframe,
                            com.sb.backend.models.Candle(
                                key.symbol, timeframe, candle.timestamp,
                                candle.open, candle.high, candle.low, candle.close,
                                candle.volume, pair.second, key.exchange
                            ),
                            "Worker queue saturation fallback"
                        )
                        if (!saved) {
                            recordError(IllegalStateException("Saturation fallback could not enqueue candle"))
                        }
                    }
                }
        } catch (fallbackError: Exception) {
            recordError(fallbackError)
        }
    }

    /**
     * Worker-only drain. Destination write failure leaves the queue row in DB
     * for later recovery; no pending row is removed before successful save.
     */
    private fun drainPendingCandleQueue() {
        if (!BackendManager.isInitialized()) return
        val delays = longArrayOf(300L, 600L, 1_200L, 2_400L)
        val repository = BackendManager.market()
        // Drain multiple pages so startup recovery is not capped to the first
        // 500 rows. Stop the pass when a page has failures to avoid tight loops.
        var page = 0
        while (page++ < 100) {
            val pending = repository.loadPendingCandlePersistence(500)
            if (pending.isEmpty()) break
            var pageFailed = false
            pending.forEach { item ->
                var saved = false
                for (attempt in 0..delays.size) {
                    val result = MarketFeedRepository.saveCandles(
                        InstrumentKey(item.exchange, item.symbol),
                        item.timeframe,
                        listOf(MarketCandle(
                            item.candle.timestamp, item.candle.open, item.candle.high,
                            item.candle.low, item.candle.close, item.candle.volume
                        )),
                        item.candle.isComplete
                    )
                    if (result == 1) {
                        repository.removePendingCandlePersistence(item)
                        savedCandles.incrementAndGet()
                        saved = true
                        break
                    }
                    repository.markPendingCandlePersistenceFailure(
                        item, "Destination write returned " + result
                    )
                    if (attempt < delays.size) {
                        try { Thread.sleep(delays[attempt]) } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return
                        }
                    }
                }
                if (!saved) {
                    pageFailed = true
                    failedWrites.incrementAndGet()
                    recordError(IllegalStateException(
                        "Candle remains pending durable queue: " +
                            item.exchange + ":" + item.symbol + "/" + item.timeframe +
                            "@" + item.candle.timestamp
                    ))
                }
            }
            if (pageFailed) break
        }
        publishStatus(force = true)
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
        } catch (e: RejectedExecutionException) {
            recordError(e)
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
            lastError = lastError,
            persistenceMode = currentPersistenceMode(),
            savedCandles = savedCandles.get(),
            failedWrites = failedWrites.get(),
            queueDepth = (executorRef as? ThreadPoolExecutor)?.queue?.size ?: 0,
            synchronousWrites = synchronousWrites.get()
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
