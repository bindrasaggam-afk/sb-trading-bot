package com.sb.backend.market

import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.market.model.MarketTick
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

object MarketDataManager {

    private val ticks = ConcurrentHashMap<String, MarketTick>()
    private val instrumentTicks = ConcurrentHashMap<String, MarketTick?>()

    private val subscribedKeys = CopyOnWriteArraySet<String>()
    private val activeSubscribers = ConcurrentHashMap<String, Int>()
    private val tickListeners = CopyOnWriteArraySet<(MarketTick) -> Unit>()

    @Volatile
    private var connectionState = ConnectionState.DISCONNECTED

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATED, RECONNECTING, ERROR
    }

    fun updateConnection(state: ConnectionState) {
        connectionState = state
    }

    fun currentConnection(): ConnectionState = connectionState

    fun onNewTick(tick: MarketTick) {
        val key = tick.key.key()
        ticks[key] = tick
        instrumentTicks[key] = tick
        tickListeners.forEach { listener ->
            try {
                listener(tick)
            } catch (_: Exception) {
            }
        }
    }

    fun getTick(key: InstrumentKey): MarketTick? = ticks[key.key()]

    fun getTickValue(key: InstrumentKey): MarketTick? = instrumentTicks[key.key()]

    fun allTicks(): Map<String, MarketTick> = HashMap(ticks)

    fun addTickListener(listener: (MarketTick) -> Unit) {
        tickListeners.add(listener)
    }

    fun removeTickListener(listener: (MarketTick) -> Unit) {
        tickListeners.remove(listener)
    }

    fun subscribe(key: InstrumentKey): Boolean {
        val k = key.key()
        val count = (activeSubscribers[k] ?: 0) + 1
        activeSubscribers[k] = count
        return subscribedKeys.add(k)
    }

    fun unsubscribe(key: InstrumentKey): Boolean {
        val k = key.key()
        val count = (activeSubscribers[k] ?: 0) - 1
        return if (count <= 0) {
            activeSubscribers.remove(k)
            subscribedKeys.remove(k)
        } else {
            activeSubscribers[k] = count
            false
        }
    }

    fun isSubscribed(key: InstrumentKey): Boolean = subscribedKeys.contains(key.key())

    fun clear() {
        ticks.clear()
        instrumentTicks.clear()
        subscribedKeys.clear()
        activeSubscribers.clear()
    }
}
