package com.sb.ui.market

import androidx.lifecycle.ViewModel
import com.sb.backend.market.MarketDataManager
import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.market.model.MarketUiState
import com.sb.backend.market.repo.MarketFeedRepository

class InstrumentLiveViewModel(
    private val repository: MarketFeedRepository = MarketFeedRepository()
) : ViewModel() {

    private var currentKey: InstrumentKey? = null

    var uiState: MarketUiState = MarketUiState()
        private set

    fun setInstrument(exchange: String, symbol: String) {
        val key = InstrumentKey(exchange, symbol)
        currentKey = key
        subscribe(key)
    }

    fun subscribe(key: InstrumentKey) {
        currentKey = key
        repository.subscribe(key)
        refresh()
    }

    fun unsubscribe(key: InstrumentKey) {
        repository.unsubscribe(key)
        refresh()
    }

    fun refresh() {
        val key = currentKey
        val connection = repository.observeConnection()
        uiState = MarketUiState(
            tick = key?.let { repository.observeTick(it) },
            isSubscribed = key?.let { repository.isSubscribed(it) } ?: false,
            isConnected = connection == MarketDataManager.ConnectionState.CONNECTED ||
                connection == MarketDataManager.ConnectionState.AUTHENTICATED,
            isAuthenticated = connection == MarketDataManager.ConnectionState.AUTHENTICATED,
            status = connection.name
        )
    }

    override fun onCleared() {
        currentKey?.let { repository.unsubscribe(it) }
        currentKey = null
        super.onCleared()
    }
}
