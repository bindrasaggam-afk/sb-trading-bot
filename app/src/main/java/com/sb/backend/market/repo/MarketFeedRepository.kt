package com.sb.backend.market.repo

import com.sb.backend.market.MarketDataManager
import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.market.model.MarketTick

class MarketFeedRepository {

    fun observeTick(key: InstrumentKey): MarketTick? {
        return MarketDataManager.getTick(key)
    }

    fun observeTicks(): Map<String, MarketTick> {
        return MarketDataManager.allTicks()
    }

    fun observeConnection(): MarketDataManager.ConnectionState {
        return MarketDataManager.currentConnection()
    }

    fun addTickListener(listener: (MarketTick) -> Unit) {
        MarketDataManager.addTickListener(listener)
    }

    fun removeTickListener(listener: (MarketTick) -> Unit) {
        MarketDataManager.removeTickListener(listener)
    }

    fun getLatestTick(key: InstrumentKey): MarketTick? {
        return MarketDataManager.getTick(key)
    }

    fun subscribe(key: InstrumentKey) = MarketDataManager.subscribe(key)

    fun unsubscribe(key: InstrumentKey) = MarketDataManager.unsubscribe(key)

    fun isSubscribed(key: InstrumentKey) = MarketDataManager.isSubscribed(key)
}
