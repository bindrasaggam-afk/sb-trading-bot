package com.sb.backend.market.repo

import com.sb.backend.core.BackendManager
import com.sb.backend.market.MarketDataManager
import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.market.model.MarketCandle
import com.sb.backend.market.model.MarketTick
import com.sb.backend.services.KotakSessionCoordinator
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

object MarketFeedRepository {

    fun observeTicks(): StateFlow<Map<String, MarketTick>> =
        MarketDataManager.ticks

    fun observeTick(key: InstrumentKey): StateFlow<MarketTick?> =
        MarketDataManager.tickFlow(key)

    fun observeTickStream(): SharedFlow<MarketTick> =
        MarketDataManager.tickStream

    fun observeConnection(): StateFlow<MarketDataManager.ConnectionState> =
        MarketDataManager.connectionState

    fun addTickListener(listener: (MarketTick) -> Unit) {
        MarketDataManager.addTickListener(listener)
    }

    fun removeTickListener(listener: (MarketTick) -> Unit) {
        MarketDataManager.removeTickListener(listener)
    }

    fun getLatestTick(key: InstrumentKey): MarketTick? =
        MarketDataManager.getTick(key)

    fun history(key: InstrumentKey): List<MarketTick> =
        MarketDataManager.history(key)

    fun subscribe(key: InstrumentKey) {
        val added = MarketDataManager.subscribe(key)
        if (added) {
            KotakSessionCoordinator.requestSubscription(
                symbol = key.symbol,
                exchange = key.exchange
            )
        }
    }

    fun unsubscribe(key: InstrumentKey) {
        val removed = MarketDataManager.unsubscribe(key)
        if (removed) {
            KotakSessionCoordinator.requestUnsubscription(
                symbol = key.symbol,
                exchange = key.exchange
            )
        }
    }

    fun requestSubscription(key: InstrumentKey) {
        KotakSessionCoordinator.requestSubscription(
            symbol = key.symbol,
            exchange = key.exchange
        )
    }

    fun isSubscribed(key: InstrumentKey): Boolean =
        MarketDataManager.isSubscribed(key)

    fun loadStoredCandles(
        key: InstrumentKey,
        timeframe: String,
        limit: Int = 2_000
    ): List<MarketCandle> {
        if (!BackendManager.isInitialized()) return emptyList()
        return try {
            BackendManager.market()
                .getCandles(
                    symbol = key.symbol,
                    timeframe = timeframe,
                    exchange = key.exchange,
                    limit = limit
                )
                .map {
                    MarketCandle(
                        timestamp = it.timestamp,
                        open = it.open,
                        high = it.high,
                        low = it.low,
                        close = it.close,
                        volume = it.volume
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCandles(
        key: InstrumentKey,
        timeframe: String,
        candles: List<MarketCandle>
    ): Int {
        if (!BackendManager.isInitialized() || candles.isEmpty()) return 0
        return try {
            BackendManager.market().saveCandles(
                symbol = key.symbol,
                exchange = key.exchange,
                timeframe = timeframe,
                candles = candles.map {
                    com.sb.backend.models.Candle(
                        symbol = key.symbol,
                        timeframe = timeframe,
                        timestamp = it.timestamp,
                        open = it.open,
                        high = it.high,
                        low = it.low,
                        close = it.close,
                        volume = it.volume,
                        isComplete = true
                    )
                }
            )
        } catch (_: Exception) {
            0
        }
    }

    fun loadHistoricalCandles(
        key: InstrumentKey,
        timeframe: String,
        years: Int = 3,
        callback: (List<MarketCandle>) -> Unit
    ) {
        KotakSessionCoordinator.loadHistoricalCandles(
            symbol = key.symbol,
            exchange = key.exchange,
            timeframe = timeframe,
            years = years
        ) { candles ->
            callback(
                candles.map {
                    MarketCandle(
                        timestamp = it.timestamp,
                        open = it.open,
                        high = it.high,
                        low = it.low,
                        close = it.close,
                        volume = it.volume
                    )
                }
            )
        }
    }
}
