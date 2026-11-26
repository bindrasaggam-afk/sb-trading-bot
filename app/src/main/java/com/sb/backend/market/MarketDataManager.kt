
package com.sb.backend.market

import com.sb.backend.market.engine.CandleEngine

import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.market.model.MarketTick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

object MarketDataManager {

    private val candleEngine = CandleEngine()


    const val MAX_HISTORY_PER_INSTRUMENT = 2400
    const val MAX_CACHED_INSTRUMENTS = 10_000

    private const val SNAPSHOT_INTERVAL_MS = 500L

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        AUTHENTICATED,
        RECONNECTING,
        ERROR
    }

    // ------------------------------------------------------------
    // LATEST TICKS
    // ------------------------------------------------------------

    private val latestTicks =
        ConcurrentHashMap<String, MarketTick>()

    private val _ticks =
        MutableStateFlow<Map<String, MarketTick>>(emptyMap())

    val ticks: StateFlow<Map<String, MarketTick>> =
        _ticks.asStateFlow()

    // ------------------------------------------------------------
    // LIVE TICK STREAM
    // ------------------------------------------------------------

    private val _tickStream = MutableSharedFlow<MarketTick>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val tickStream: SharedFlow<MarketTick> =
        _tickStream.asSharedFlow()

    // ------------------------------------------------------------
    // PER-INSTRUMENT STATE
    // ------------------------------------------------------------

    private val instrumentFlows =
        ConcurrentHashMap<String, MutableStateFlow<MarketTick?>>()

    private val history =
        ConcurrentHashMap<String, ArrayDeque<MarketTick>>()

    // LRU metadata for in-memory tick/history/candle state. Flow objects are
    // retained so existing StateFlow collectors are never detached by eviction.
    private val cacheAccess = ConcurrentHashMap<String, Long>()

    // ------------------------------------------------------------
    // SUBSCRIPTION MANAGEMENT
    // ------------------------------------------------------------

    private val subscribedKeys =
        CopyOnWriteArraySet<String>()

    private val activeSubscribers =
        ConcurrentHashMap<String, Int>()

    private val subscriptionLock = Any()

    // ------------------------------------------------------------
    // TICK LISTENERS
    // ------------------------------------------------------------

    private val tickListeners =
        CopyOnWriteArraySet<(MarketTick) -> Unit>()

    private val candleResultListeners =
        CopyOnWriteArraySet<(MarketTick, CandleEngine.TickResult) -> Unit>()

    // ------------------------------------------------------------
    // CONNECTION STATE
    // ------------------------------------------------------------

    private val _connectionState =
        MutableStateFlow(ConnectionState.DISCONNECTED)

    val connectionState: StateFlow<ConnectionState> =
        _connectionState.asStateFlow()

    // ------------------------------------------------------------
    // COROUTINE SCOPE
    // ------------------------------------------------------------

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val snapshotLock = Any()

    @Volatile
    private var snapshotJob: Job? = null

    init {
        start()
    }

    fun start() {
        synchronized(snapshotLock) {
            if (snapshotJob?.isActive == true) return
            snapshotJob = scope.launch {
                while (true) {
                    delay(SNAPSHOT_INTERVAL_MS)
                    publishLatestSnapshot()
                }
            }
        }
    }

    fun shutdown() {
        val job = synchronized(snapshotLock) {
            val current = snapshotJob
            snapshotJob = null
            current
        }
        job?.cancel()
    }

    fun getCandles(
        key: InstrumentKey,
        timeframe: com.sb.backend.market.model.MarketTimeframe
    ): List<com.sb.backend.market.model.MarketCandle> =
        candleEngine.candles(key, timeframe)

    fun seedCandles(
        key: InstrumentKey,
        candles: List<com.sb.backend.market.model.MarketCandle>,
        timeframe: com.sb.backend.market.model.MarketTimeframe =
            com.sb.backend.market.model.MarketTimeframe.MIN_1
    ) {
        candleEngine.seed(key, timeframe, candles)
        if (candles.isNotEmpty()) touchCache(key.key())
    }

    fun candleAt(
        key: InstrumentKey,
        timeframe: com.sb.backend.market.model.MarketTimeframe,
        timestamp: Long
    ): com.sb.backend.market.model.MarketCandle? =
        candleEngine.candleAt(key, timeframe, timestamp)

    fun latestCandle(
        key: InstrumentKey,
        timeframe: com.sb.backend.market.model.MarketTimeframe
    ): com.sb.backend.market.model.MarketCandle? =
        candleEngine.candles(key, timeframe).lastOrNull()

    fun candleDiagnostics(): CandleEngine.Diagnostics =
        candleEngine.diagnostics()

    fun clearCandles() {
        candleEngine.clear()
    }

    // ------------------------------------------------------------
    // CONNECTION
    // ------------------------------------------------------------

    fun updateConnection(state: ConnectionState) {
        _connectionState.value = state
    }

    fun currentConnection(): ConnectionState =
        _connectionState.value

    // ------------------------------------------------------------
    // TICK PROCESSING
    // ------------------------------------------------------------

    fun onNewTick(tick: MarketTick): CandleEngine.TickResult {

        // Reject invalid ticks before they reach any consumer.
        if (!tick.isValid) {
            return CandleEngine.TickResult(accepted = false)
        }

        // CandleEngine is the authoritative gate for accepted ticks.
        // It also rejects invalid prices and duplicate/out-of-order events
        // according to its own processing rules.
        val result = candleEngine.addTick(tick)

        if (!result.accepted) {
            return result
        }

        val key = tick.key.key()
        touchCache(key)

        // Publish only ticks accepted by CandleEngine.
        latestTicks[key] = tick

        val flow = instrumentFlows.computeIfAbsent(key) {
            MutableStateFlow(latestTicks[key])
        }

        flow.value = tick

        if (isSubscribedKeyActive(key)) {
            appendHistory(key, tick)
        }

        tickListeners.forEach { listener ->
            try {
                listener(tick)
            } catch (_: Exception) {
                // Isolate listener failures.
            }
        }

        candleResultListeners.forEach { listener ->
            try {
                listener(tick, result)
            } catch (_: Exception) {
                // Isolate listener failures.
            }
        }

        _tickStream.tryEmit(tick)

        return result
    }

    private fun appendHistory(
        key: String,
        tick: MarketTick
    ) {
        val buffer = history.computeIfAbsent(key) {
            ArrayDeque()
        }

        synchronized(buffer) {
            buffer.addLast(tick)

            while (buffer.size > MAX_HISTORY_PER_INSTRUMENT) {
                buffer.removeFirst()
            }
        }
    }

    private fun isSubscribedKeyActive(key: String): Boolean {
        return (activeSubscribers[key] ?: 0) > 0
    }

    private fun touchCache(key: String) {
        cacheAccess[key] = System.nanoTime()
        trimInactiveCaches()
    }

    /**
     * Evicts least-recently-used inactive instrument data when the soft cap is
     * exceeded. Active subscriptions are never evicted. Candle rows in SQLite
     * are untouched. Existing StateFlow instances are reset, not removed, so
     * collectors keep receiving future updates.
     *
     * The optional limit is internal-test friendly; production uses the 10k cap.
     */
    @Synchronized
    internal fun trimInactiveCaches(maxEntries: Int = MAX_CACHED_INSTRUMENTS) {
        require(maxEntries > 0) { "maxEntries must be positive" }
        synchronized(subscriptionLock) {
            val excess = cacheAccess.size - maxEntries
            if (excess <= 0) return
            val candidates = cacheAccess.entries
                .filter { (key, _) -> (activeSubscribers[key] ?: 0) <= 0 }
                .sortedBy { it.value }
            var removed = 0
            for (entry in candidates) {
                if (removed >= excess) break
                val key = entry.key
                if ((activeSubscribers[key] ?: 0) > 0) continue
                cacheAccess.remove(key, entry.value)
                latestTicks.remove(key)
                history.remove(key)
                instrumentFlows.remove(key)?.value = null
                val separator = key.indexOf('|')
                if (separator > 0 && separator < key.lastIndex) {
                    try {
                        candleEngine.reset(InstrumentKey(
                            exchange = key.substring(0, separator),
                            symbol = key.substring(separator + 1)
                        ))
                    } catch (_: IllegalArgumentException) {
                        // Invalid cache key: the tick maps are still safely cleared.
                    }
                }
                removed++
            }
        }
    }

    /** Current number of instrument cache entries, excluding lightweight flows. */
    internal fun cachedInstrumentCount(): Int = cacheAccess.size

    internal fun cachedFlowCount(): Int = instrumentFlows.size

    // ------------------------------------------------------------
    // SNAPSHOT
    // ------------------------------------------------------------

    private fun publishLatestSnapshot() {
        _ticks.value = HashMap(latestTicks)
    }

    // ------------------------------------------------------------
    // TICK ACCESS
    // ------------------------------------------------------------

    fun getTick(key: InstrumentKey): MarketTick? =
        latestTicks[key.key()]

    fun getTickValue(key: InstrumentKey): MarketTick? =
        getTick(key)

    fun allTicks(): Map<String, MarketTick> =
        HashMap(latestTicks)

    // ------------------------------------------------------------
    // PER-INSTRUMENT FLOW
    // ------------------------------------------------------------

    fun tickFlow(key: InstrumentKey): StateFlow<MarketTick?> {
        val normalizedKey = key.key()

        touchCache(normalizedKey)
        val flow = instrumentFlows.computeIfAbsent(normalizedKey) {
            MutableStateFlow(latestTicks[normalizedKey])
        }

        return flow.asStateFlow()
    }

    // ------------------------------------------------------------
    // HISTORY
    // ------------------------------------------------------------

    fun history(key: InstrumentKey): List<MarketTick> {
        touchCache(key.key())
        val buffer = history[key.key()]
            ?: return emptyList()

        synchronized(buffer) {
            return ArrayList(buffer)
        }
    }

    // ------------------------------------------------------------
    // LISTENER MANAGEMENT
    // ------------------------------------------------------------

    fun addTickListener(listener: (MarketTick) -> Unit) {
        tickListeners.add(listener)
    }

    fun removeTickListener(listener: (MarketTick) -> Unit) {
        tickListeners.remove(listener)
    }

    fun addCandleResultListener(
        listener: (MarketTick, CandleEngine.TickResult) -> Unit
    ) {
        candleResultListeners.add(listener)
    }

    fun removeCandleResultListener(
        listener: (MarketTick, CandleEngine.TickResult) -> Unit
    ) {
        candleResultListeners.remove(listener)
    }

    // ------------------------------------------------------------
    // SUBSCRIBE
    // ------------------------------------------------------------

    fun subscribe(key: InstrumentKey): Boolean {
        val normalizedKey = key.key()

        synchronized(subscriptionLock) {

            val currentCount =
                activeSubscribers[normalizedKey] ?: 0

            check(currentCount < Int.MAX_VALUE) {
                "Maximum subscriber count reached for $normalizedKey"
            }

            activeSubscribers[normalizedKey] =
                currentCount + 1
            touchCache(normalizedKey)

            return subscribedKeys.add(normalizedKey)
        }
    }

    // ------------------------------------------------------------
    // UNSUBSCRIBE
    // ------------------------------------------------------------

    fun unsubscribe(key: InstrumentKey): Boolean {
        val normalizedKey = key.key()

        synchronized(subscriptionLock) {

            val currentCount =
                activeSubscribers[normalizedKey] ?: return false

            if (currentCount <= 1) {
                activeSubscribers.remove(normalizedKey)
                subscribedKeys.remove(normalizedKey)
                return true
            }

            activeSubscribers[normalizedKey] =
                currentCount - 1

            return false
        }
    }

    // ------------------------------------------------------------
    // SUBSCRIPTION STATUS
    // ------------------------------------------------------------

    fun isSubscribed(key: InstrumentKey): Boolean =
        subscribedKeys.contains(key.key())

    fun activeKeys(): List<InstrumentKey> {
        return subscribedKeys.mapNotNull { raw ->
            val separator = raw.indexOf('|')
            if (separator <= 0 || separator >= raw.lastIndex) {
                null
            } else {
                try {
                    InstrumentKey(
                        exchange = raw.substring(0, separator),
                        symbol = raw.substring(separator + 1)
                    )
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }
    }

    // ------------------------------------------------------------
    // SUBSCRIBER COUNT
    // ------------------------------------------------------------

    fun subscriberCount(key: InstrumentKey): Int =
        activeSubscribers[key.key()] ?: 0

    // ------------------------------------------------------------
    // CLEAR
    // ------------------------------------------------------------

    fun clear() {
        candleEngine.clear()

        latestTicks.clear()

        // Reset existing flows instead of removing them.
        // Existing collectors continue receiving state updates.
        instrumentFlows.values.forEach { flow ->
            flow.value = null
        }

        history.clear()
        cacheAccess.clear()
        instrumentFlows.clear()

        synchronized(subscriptionLock) {
            subscribedKeys.clear()
            activeSubscribers.clear()
        }

        tickListeners.clear()

        _ticks.value = emptyMap()

        // Connection state is intentionally not reset here.
        // Connection lifecycle belongs to the feed/session manager.
    }
}
