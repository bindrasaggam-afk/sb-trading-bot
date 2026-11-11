package com.sb.ui.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sb.backend.market.MarketDataManager
import com.sb.backend.market.TimeframeBuckets
import com.sb.backend.market.engine.CandleEngine
import com.sb.backend.market.model.ChartStatus
import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.market.model.MarketCandle
import com.sb.backend.market.model.MarketTick
import com.sb.backend.market.model.MarketTimeframe
import com.sb.backend.market.model.MarketUiState
import com.sb.backend.market.repo.MarketFeedRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

class InstrumentLiveViewModel(
    private val repository: MarketFeedRepository
) : ViewModel() {

    constructor() : this(MarketFeedRepository)

    private val engine = CandleEngine()
    private val instrumentKey = MutableStateFlow<InstrumentKey?>(null)
    private val timeframe = MutableStateFlow(MarketTimeframe.MIN_1)

    private val _candles = MutableStateFlow<List<MarketCandle>>(emptyList())
    val candles: StateFlow<List<MarketCandle>> = _candles.asStateFlow()

    private val _chartStatus = MutableStateFlow(
        ChartStatus(state = "IDLE", message = "No instrument selected")
    )
    val chartStatus: StateFlow<ChartStatus> = _chartStatus.asStateFlow()

    val selectedTimeframe: StateFlow<MarketTimeframe> = timeframe.asStateFlow()

    @Volatile
    private var currentKey: InstrumentKey? = null

    private val candleLock = Any()
    private val generation = AtomicInteger(0)
    private var liveTargetBucket = 0L
    private var liveActive = false
    private var historicalLoaded = false
    private var historicalUnavailable = false
    private var historicalMessage = ""

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<MarketUiState> = combine(
        instrumentKey,
        repository.observeConnection(),
        instrumentKey.flatMapLatest { key ->
            if (key == null) flowOf(null) else repository.observeTick(key)
        },
        timeframe,
        _chartStatus
    ) { key, connection, tick, tf, status ->

        val subscribed = key?.let { repository.isSubscribed(it) } ?: false

        MarketUiState(
            key = key,
            tick = tick,
            isSubscribed = subscribed,
            connectionState = connection,
            timeframe = tf.label,
            status = if (key == null) "NO_INSTRUMENT" else status.state,
            lastUpdated = tick?.receivedAt ?: 0L,
            chartStatus = status
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MarketUiState()
    )

    init {
        viewModelScope.launch {
            repository.observeTickStream().collect { tick ->
                val active = currentKey ?: return@collect
                if (!sameInstrument(tick.key, active)) return@collect
                handleTick(tick)
            }
        }
    }

    fun setInstrument(exchange: String, symbol: String) {
        setInstrument(InstrumentKey(exchange = exchange, symbol = symbol))
    }

    fun setInstrument(key: InstrumentKey) {
        val normalized = InstrumentKey(
            exchange = key.exchange.trim().toUpperCase(Locale.ROOT),
            symbol = key.symbol.trim()
        )
        if (normalized.exchange.isEmpty() || normalized.symbol.isEmpty()) return

        val oldKey = currentKey
        if (oldKey != null && sameInstrument(oldKey, normalized)) return

        val token = generation.incrementAndGet()
        oldKey?.let { repository.unsubscribe(it) }

        synchronized(candleLock) {
            currentKey = normalized
            instrumentKey.value = normalized
            engine.clear()
            _candles.value = emptyList()
            liveTargetBucket = 0L
            liveActive = false
            historicalLoaded = false
            historicalUnavailable = false
            historicalMessage = ""
            publishStatusLocked()
        }

        repository.subscribe(normalized)
        loadStoredCandles(normalized, timeframe.value, token)
        requestHistoricalCandles(normalized, timeframe.value, token)
    }

    fun setTimeframe(label: String) {
        val parsed = MarketTimeframe.fromLabel(label) ?: return
        if (timeframe.value == parsed) return

        val key = currentKey
        if (key == null) {
            timeframe.value = parsed
            return
        }

        val token = generation.incrementAndGet()

        synchronized(candleLock) {
            timeframe.value = parsed
            liveTargetBucket = 0L
            historicalLoaded = false
            historicalUnavailable = false
            historicalMessage = ""
            _candles.value = engine.candles(key, parsed)
            publishStatusLocked()
        }

        loadStoredCandles(key, parsed, token)
        requestHistoricalCandles(key, parsed, token)
    }

    fun releaseInstrument() {
        generation.incrementAndGet()
        val oldKey = currentKey
        oldKey?.let { repository.unsubscribe(it) }

        synchronized(candleLock) {
            currentKey = null
            instrumentKey.value = null
            engine.clear()
            _candles.value = emptyList()
            liveTargetBucket = 0L
            liveActive = false
            historicalLoaded = false
            historicalUnavailable = false
            historicalMessage = ""
            publishStatusLocked()
        }
    }

    private fun handleTick(tick: MarketTick) {
        if (tick.ltp <= 0.0 || !tick.ltp.isFinite()) return

        val key = currentKey ?: return
        if (!sameInstrument(tick.key, key)) return

        var finalizedBase: List<MarketCandle> = emptyList()
        var finalizedTarget: MarketCandle? = null
        var finalizedTargetLabel: String? = null

        synchronized(candleLock) {
            val active = currentKey ?: return
            if (!sameInstrument(active, key)) return

            val result = engine.addTick(tick)
            if (!result.accepted) return

            val activeTimeframe = timeframe.value
            val target = engine.candleAt(
                key = key,
                timeframe = activeTimeframe,
                timestamp = result.baseTimestamp
            ) ?: return

            val existing = _candles.value.firstOrNull {
                it.timestamp == target.timestamp
            }

            if (liveTargetBucket != 0L && target.timestamp > liveTargetBucket) {
                val previous = _candles.value.firstOrNull {
                    it.timestamp == liveTargetBucket
                }
                if (previous != null) {
                    finalizedTarget = previous
                    finalizedTargetLabel = activeTimeframe.label
                }
            }

            liveTargetBucket = target.timestamp
            liveActive = true
            finalizedBase = result.finalizedBase

            _candles.value = upsertCandle(
                current = _candles.value,
                candle = mergeLiveTarget(existing, target)
            )

            publishStatusLocked()
        }

        if (finalizedBase.isNotEmpty()) {
        }
        val targetToPersist = finalizedTarget
        val targetLabel = finalizedTargetLabel
        if (targetToPersist != null && targetLabel != null) {
        }
    }

    private fun loadStoredCandles(
        key: InstrumentKey,
        requested: MarketTimeframe,
        token: Int
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val storedTarget = repository.loadStoredCandles(
                key = key,
                timeframe = requested.label,
                limit = MAX_CANDLES
            )
            val storedBase = if (requested == MarketTimeframe.MIN_1) {
                storedTarget
            } else {
                repository.loadStoredCandles(
                    key = key,
                    timeframe = MarketTimeframe.MIN_1.label,
                    limit = MAX_BASE_CANDLES
                )
            }

            if (generation.get() != token) return@launch

            synchronized(candleLock) {
                if (!isCurrent(key, requested, token)) return@synchronized

                if (storedBase.isNotEmpty()) {
                    engine.seedBase(key, storedBase)
                }

                val live = engine.candles(key, requested)
                val merged = mergeAll(
                    listOf(storedTarget, _candles.value, live)
                )
                if (merged.isNotEmpty()) {
                    _candles.value = merged
                }
                publishStatusLocked()
            }
        }
    }

    private fun requestHistoricalCandles(
        key: InstrumentKey,
        requested: MarketTimeframe,
        token: Int
    ) {
        repository.loadHistoricalCandles(
            key = key,
            timeframe = requested.label,
            years = HISTORICAL_YEARS
        ) { historical ->
            if (generation.get() != token) return@loadHistoricalCandles

            synchronized(candleLock) {
                if (!isCurrent(key, requested, token)) return@synchronized

                val valid = historical
                    .map { normalizeCandle(it) }
                    .filter { isValidCandle(it) }

                if (valid.isEmpty()) {
                    historicalLoaded = false
                    historicalUnavailable = true
                    historicalMessage = "Historical data unavailable • live only"
                } else {
                    val live = engine.candles(key, requested)
                    _candles.value = mergeAll(
                        listOf(valid, _candles.value, live)
                    )
                    historicalLoaded = true
                    historicalUnavailable = false
                    historicalMessage = ""
                }
                publishStatusLocked()
            }
        }
    }

    private fun isCurrent(
        key: InstrumentKey,
        requested: MarketTimeframe,
        token: Int
    ): Boolean {
        val active = currentKey ?: return false
        return generation.get() == token &&
            sameInstrument(active, key) &&
            timeframe.value == requested
    }

    private fun mergeLiveTarget(
        existing: MarketCandle?,
        target: MarketCandle
    ): MarketCandle {
        if (existing == null) return target
        return target.copy(
            open = existing.open,
            high = max(existing.high, target.high),
            low = min(existing.low, target.low),
            close = target.close,
            volume = max(existing.volume, target.volume)
        )
    }

    private fun mergeCandles(
        base: List<MarketCandle>,
        live: List<MarketCandle>
    ): List<MarketCandle> {
        val merged = LinkedHashMap<Long, MarketCandle>()

        base.forEach { candle ->
            val normalized = normalizeCandle(candle)
            if (isValidCandle(normalized)) {
                merged[normalized.timestamp] = normalized
            }
        }

        live.forEach { candle ->
            val normalized = normalizeCandle(candle)
            if (!isValidCandle(normalized)) return@forEach

            val existing = merged[normalized.timestamp]
            merged[normalized.timestamp] = if (existing == null) {
                normalized
            } else {
                normalized.copy(
                    open = existing.open,
                    high = max(existing.high, normalized.high),
                    low = min(existing.low, normalized.low),
                    close = normalized.close,
                    volume = max(existing.volume, normalized.volume)
                )
            }
        }

        return merged.values
            .sortedBy { it.timestamp }
            .takeLast(MAX_CANDLES)
    }

    /**
     * Unions multiple candle sources without dropping data.
     *
     * Sources are applied in increasing priority order: the first source keeps
     * its open/high/low, later sources can extend the range and override the
     * close. This prevents a late stored/historical load from wiping candles
     * that were already merged from the other source (multi-timeframe race).
     */
    private fun mergeAll(sources: List<List<MarketCandle>>): List<MarketCandle> {
        var merged: List<MarketCandle> = emptyList()
        sources.forEach { series ->
            if (series.isNotEmpty()) {
                merged = mergeCandles(merged, series)
            }
        }
        return merged
    }

    private fun upsertCandle(
        current: List<MarketCandle>,
        candle: MarketCandle
    ): List<MarketCandle> {
        val result = ArrayList<MarketCandle>(current.size + 1)
        var inserted = false

        current.forEach {
            when {
                it.timestamp < candle.timestamp -> result.add(it)
                it.timestamp == candle.timestamp -> {
                    result.add(candle)
                    inserted = true
                }
                !inserted -> {
                    result.add(candle)
                    result.add(it)
                    inserted = true
                }
                else -> result.add(it)
            }
        }

        if (!inserted) result.add(candle)

        return if (result.size > MAX_CANDLES) {
            ArrayList(result.subList(result.size - MAX_CANDLES, result.size))
        } else {
            result
        }
    }

    private fun publishStatusLocked() {
        val count = _candles.value.size
        val key = currentKey

        val state = when {
            key == null -> "IDLE"
            liveActive && count > 0 -> "LIVE_ACTIVE"
            liveActive -> "CANDLE_BUILDING"
            count > 0 -> "STORED_DATA"
            historicalUnavailable -> "HISTORICAL_UNAVAILABLE"
            else -> "WAITING_LIVE"
        }

        val message = when {
            key == null -> "No instrument selected"
            liveActive && count > 0 && historicalUnavailable ->
                "Live candles • $historicalMessage"
            liveActive && count > 0 -> "Live candles from Kotak feed"
            liveActive -> "Receiving ticks • building candles"
            count > 0 -> "Showing stored candles • waiting for live ticks"
            historicalUnavailable -> historicalMessage
            else -> "Waiting for live ticks"
        }

        _chartStatus.value = ChartStatus(
            state = state,
            message = message,
            candleCount = count,
            historicalLoaded = historicalLoaded,
            liveActive = liveActive
        )
    }

    private fun normalizeCandle(candle: MarketCandle): MarketCandle {
        val timestamp = TimeframeBuckets.normalizeTimestamp(candle.timestamp)
        return if (timestamp == candle.timestamp) candle
        else candle.copy(timestamp = timestamp)
    }

    private fun isValidCandle(candle: MarketCandle): Boolean = candle.isValid

    private fun sameInstrument(first: InstrumentKey, second: InstrumentKey): Boolean {
        return first.key().equals(second.key(), ignoreCase = true)
    }

    override fun onCleared() {
        generation.incrementAndGet()
        val oldKey = currentKey
        oldKey?.let { repository.unsubscribe(it) }
        synchronized(candleLock) {
            currentKey = null
            engine.clear()
            _candles.value = emptyList()
        }
        super.onCleared()
    }

    private companion object {
        private const val MAX_CANDLES = 2_000
        private const val MAX_BASE_CANDLES = 6_000
        private const val HISTORICAL_YEARS = 3
    }
}
