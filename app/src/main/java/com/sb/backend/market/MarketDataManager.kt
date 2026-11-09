
package com.sb.backend.market

import com.sb.backend.market.engine.CandleEngine

import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.market.model.MarketTick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    init {
        scope.launch {
            while (true) {
                delay(SNAPSHOT_INTERVAL_MS)
                publishLatestSnapshot()
            }
        }
    }

    fun getCandles(
        key: InstrumentKey,
        timeframe: com.sb.backend.market.model.MarketTimeframe
    ): List<com.sb.backend.market.model.MarketCandle> =
        candleEngine.candles(key, timeframe)

    fun seedCandles(
        key: InstrumentKey,
        candles: List<com.sb.backend.market.model.MarketCandle>
    ) = candleEngine.seedBase(key, candles)

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

        val key = tick.key.key()

        // Keep latest tick immediately available.
        latestTicks[key] = tick

        // Update per-instrument observers.
        val flow = instrumentFlows.computeIfAbsent(key) {
            MutableStateFlow(latestTicks[key])
        }

        flow.value = tick

        // Store history only while subscribers are active.
        if (isSubscribedKeyActive(key)) {
            appendHistory(key, tick)
        }

        // Notify registered listeners safely.
        tickListeners.forEach { listener ->
            try {
                listener(tick)
            } catch (_: Exception) {
                // One listener must not interrupt other listeners.
            }
        }

        // Publish to the shared live stream.
        _tickStream.tryEmit(tick)

        return candleEngine.addTick(tick)
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

        val flow = instrumentFlows.computeIfAbsent(normalizedKey) {
            MutableStateFlow(latestTicks[normalizedKey])
        }

        return flow.asStateFlow()
    }

    // ------------------------------------------------------------
    // HISTORY
    // ------------------------------------------------------------

    fun history(key: InstrumentKey): List<MarketTick> {
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
