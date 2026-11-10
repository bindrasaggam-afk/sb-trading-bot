package com.sb.backend.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sb.backend.core.BackendManager
import com.sb.backend.market.MarketDataManager
import com.sb.backend.market.TimeframeBuckets
import com.sb.backend.models.Candle
import com.sb.backend.security.KotakNeoSecureStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

object KotakSessionCoordinator {

    private const val TAG = "KotakSession"

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor()
    }
    private val historicalExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor()
    }

    private var appContext: Context? = null
    private var secureStore: KotakNeoSecureStore? = null
    private var autoLoginManager: KotakAutoLoginManager? = null
    private var liveFeedService: KotakNeoLiveFeedService? = null

    private val syncing = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)

    @Volatile
    private var started = false

    @Volatile
    private var lastStatus = "Connection: Not tested"

    @Volatile
    private var statusListener: ((String) -> Unit)? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            if (appContext != null) return

            val safeContext = context.applicationContext
            appContext = safeContext
            secureStore = KotakNeoSecureStore(safeContext)
        }
    }

    fun startIfEnabled() {
        val store = store()

        synchronized(lock) {
            if (started) return
            started = true
        }

        if (store.isAutoLoginEnabled()) {
            startAutoLogin()
            return
        }

        val sid = store.getTradeSid()
        val token = store.getTradeToken()

        if (sid.isNotBlank() && token.isNotBlank()) {
            updateStatus("Saved Kotak session found — connecting live feed")
            connectFeed()
        } else {
            updateStatus("Kotak auto-connect skipped: login required")
        }
    }

    fun startAutoLogin() {
        val store = store()

        synchronized(lock) {
            if (autoLoginManager?.isRunning() == true) {
                updateStatus("Auto Login already running")
                return
            }

            val manager = KotakAutoLoginManager(
                secureStore = store,
                authClient = KotakNeoAuthClient(),
                onStatus = { message ->
                    updateStatus(message)
                },
                onLoginSuccess = {
                    updateStatus(
                        "Kotak Neo authenticated — syncing scrip master"
                    )
                    syncScripMaster(forceReconnect = true)
                },
                onLoginError = { message ->
                    updateStatus("Login failed: $message")
                }
            )

            autoLoginManager?.close()
            autoLoginManager = manager
            started = true
            manager.startAutoLogin()
        }
    }

    fun stopAutoLogin() {
        synchronized(lock) {
            autoLoginManager?.stopAutoLogin()
            autoLoginManager?.close()
            autoLoginManager = null
        }

        updateStatus("Auto Login OFF")
    }

    fun syncScripMaster(forceReconnect: Boolean = true) {
        val context = context()

        if (!syncing.compareAndSet(false, true)) {
            updateStatus("Scrip master sync already in progress")
            return
        }

        updateStatus("Syncing Kotak scrip master...")

        executor.execute {
            try {
                BackendManager.initialize(context)

                val result = KotakScripMasterService(
                    store(),
                    BackendManager.instruments()
                ).sync()

                val errorText = if (result.errors.isEmpty()) {
                    ""
                } else {
                    "\nErrors: ${result.errors.joinToString("; ")}"
                }

                updateStatus(
                    "Scrip master synced\n" +
                        "Segments: ${result.downloadedSegments}\n" +
                        "Records: ${result.parsedRecords}\n" +
                        "Matched: ${result.matchedRecords}\n" +
                        "Tokens updated: ${result.updatedRows}\n" +
                        "MCX futures saved: ${result.mcxContractsSaved}\n" +
                        "MCX active contracts: " +
                        "${result.mcxActiveContracts}\n" +
                        "MCX commodities: ${result.mcxCommodities}\n" +
                        "MCX expired deactivated: " +
                        "${result.mcxExpiredDeactivated}\n" +
                        "MCX stale deactivated: " +
                        "${result.mcxStaleDeactivated}" +
                        errorText
                )

                if (forceReconnect) {
                    closeFeed()
                }

                connectFeed()
            } catch (e: Exception) {
                updateStatus(
                    "Scrip master sync failed: ${e.message}"
                )
            } finally {
                syncing.set(false)
            }
        }
    }

    fun connectFeed() {
        val context = context()

        if (!connecting.compareAndSet(false, true)) {
            updateStatus("Kotak Live Feed connection already in progress")
            return
        }

        updateStatus("Connecting Kotak Live Feed...")

        executor.execute {
            try {
                BackendManager.initialize(context)

                val existing = synchronized(lock) {
                    liveFeedService
                }

                if (existing?.isAuthenticated() == true ||
                    existing?.isConnected() == true
                ) {
                    updateStatus("Kotak Live Feed already connected")
                    return@execute
                }

                val instruments = BackendManager.instruments()
                    .getActiveWithTokens(2950)

                require(instruments.isNotEmpty()) {
                    "No instruments with Kotak tokens. Sync scrip master first."
                }

                val service = KotakNeoLiveFeedService(
                    secureStore = store(),
                    marketRepository = BackendManager.market(),
                    instruments = instruments,
                    onStatus = { message ->
                        updateStatus(message)
                    }
                )

                synchronized(lock) {
                    liveFeedService?.close()
                    liveFeedService = service
                }

                service.connect()
            } catch (e: Exception) {
                updateStatus("Live feed failed: ${e.message}")
            } finally {
                connecting.set(false)
            }
        }
    }

    /**
     * Subscribe one instrument on demand (used by the live chart screen).
     * The global feed already subscribes all tokenised instruments; this
     * additionally handles indices and instruments outside that snapshot.
     */
    fun requestSubscription(symbol: String, exchange: String) {
        val context = appContext

        if (context == null) {
            updateStatus("Live subscribe skipped: session not initialized")
            return
        }

        executor.execute {
            try {
                BackendManager.initialize(context)

                val instrument = BackendManager.instruments()
                    .findBySymbol(symbol, exchange)

                if (instrument == null) {
                    updateStatus(
                        "Live subscribe skipped: $exchange:$symbol not found"
                    )
                    return@execute
                }

                val service = synchronized(lock) {
                    liveFeedService
                }

                if (service == null) {
                    updateStatus(
                        "Live subscribe pending: Kotak feed not connected"
                    )
                    return@execute
                }

                service.subscribeInstrument(instrument)
            } catch (e: Exception) {
                updateStatus(
                    "Live subscribe failed for $exchange:$symbol: ${e.message}"
                )
            }
        }
    }

    fun loadHistoricalCandles(
        symbol: String,
        exchange: String,
        timeframe: String,
        years: Int = 3,
        callback: (List<Candle>) -> Unit
    ) {
        val context = appContext
        val store = secureStore

        if (context == null || store == null) {
            mainHandler.post { callback(emptyList()) }
            return
        }

        historicalExecutor.execute {
            val candles = try {
                BackendManager.initialize(context)

                val instrument = BackendManager.instruments()
                    .findBySymbol(symbol, exchange)

                if (instrument == null) {
                    emptyList()
                } else {
                    KotakNeoMarketDataService(
                        secureStore = store,
                        marketRepository = BackendManager.market()
                    ).fetchHistoricalCandles(
                        instrument = instrument,
                        timeframe = timeframe,
                        years = years
                    )
                }
            } catch (e: Exception) {
                updateStatus(
                    "Historical load failed for $exchange:$symbol: ${e.message}"
                )
                emptyList()
            }

            mainHandler.post { callback(candles) }
        }
    }


    @Volatile
    private var mcxBhavcopyService: McxBhavcopyHistoricalService? = null

    @Volatile
    private var mcxImportRunning = false

    fun startMcxThreeMonthBackfill(): JSONObject {
        synchronized(historicalImportLock) {
            if (mcxImportRunning) {
                return (mcxBhavcopyService?.snapshot()
                    ?: JSONObject().put("status", "running"))
                    .put("already_running", true)
            }
            val context = appContext
                ?: return JSONObject().put("status", "blocked")
                    .put("message", "Backend session is not initialized")
            BackendManager.initialize(context)
            val service = McxBhavcopyHistoricalService(BackendManager.market())
            mcxBhavcopyService = service
            mcxImportRunning = true
            historicalExecutor.execute {
                try {
                    service.importThreeMonths()
                } finally {
                    mcxImportRunning = false
                }
            }
            return service.snapshot().put("started", true)
        }
    }

    fun mcxHistoricalBackfillSnapshot(): JSONObject =
        mcxBhavcopyService?.snapshot()
            ?: JSONObject().put("status", "not_started")
                .put("source", "mtf.trading MCX bhavcopy archive")

    private val historicalImportLock = Any()

    @Volatile
    private var historicalImportRunning = false

    @Volatile
    private var historicalImportStatus = "not_started"

    @Volatile
    private var historicalImportTotal = 0

    @Volatile
    private var historicalImportCompleted = 0

    @Volatile
    private var historicalImportCandlesSaved = 0L

    @Volatile
    private var historicalImportMonthlyCandlesSaved = 0L

    @Volatile
    private var historicalImportSkipped = 0

    @Volatile
    private var historicalImportErrors = 0

    @Volatile
    private var historicalImportLastError = ""

    /**
     * Starts a sequential 3-year daily backfill for token-verified NSE/BSE
     * cash equities and ETFs. Progress is exposed through status snapshot.
     */
    fun startEquityHistoricalBackfill(): JSONObject {
        synchronized(historicalImportLock) {
            if (historicalImportRunning) {
                return historicalImportSnapshot().put("already_running", true)
            }
            val context = appContext
                ?: return JSONObject().put("status", "blocked")
                    .put("message", "Kotak session is not initialized")
            val store = secureStore
                ?: return JSONObject().put("status", "blocked")
                    .put("message", "Kotak secure store is unavailable")

            BackendManager.initialize(context)
            val candidates = BackendManager.instruments()
                .getActiveWithTokens(5000)
                .filter { instrument ->
                    val exchange = instrument.exchange.uppercase()
                    val segment = instrument.segment.uppercase()
                    val type = instrument.instrumentType.uppercase()
                    (exchange == "NSE" || exchange == "BSE") &&
                        (segment in setOf("EQ", "ETF", "EQUITY")) &&
                        (type in setOf("EQ", "ETF"))
                }
                .distinctBy { "${it.exchange}:${it.symbol}" }

            if (candidates.isEmpty()) {
                return JSONObject().put("status", "blocked")
                    .put("message", "No active NSE/BSE equity or ETF instruments with verified Kotak tokens")
            }
            if (store.getAccessToken().isBlank() || store.getTradeBaseUrl().isBlank()) {
                return JSONObject().put("status", "blocked")
                    .put("message", "Kotak Neo historical session is not authenticated")
            }

            historicalImportRunning = true
            historicalImportStatus = "running"
            historicalImportTotal = candidates.size
            historicalImportCompleted = 0
            historicalImportCandlesSaved = 0L
            historicalImportMonthlyCandlesSaved = 0L
            historicalImportSkipped = 0
            historicalImportErrors = 0
            historicalImportLastError = ""

            historicalExecutor.execute {
                val service = KotakNeoMarketDataService(
                    secureStore = store,
                    marketRepository = BackendManager.market()
                )
                candidates.forEach { instrument ->
                    try {
                        val saved = service.fetchHistoricalCandles(
                            instrument = instrument,
                            timeframe = "1D",
                            years = 3
                        )
                        historicalImportCandlesSaved += saved.size

                        // Rebuild calendar-month candles from persisted daily history.
                        // Keep existing monthly rows untouched when no daily source exists.
                        val market = BackendManager.market()
                        val dailyCandles = market.getCandles(
                            symbol = instrument.symbol,
                            timeframe = "1D",
                            exchange = instrument.exchange,
                            limit = 5000
                        )
                        if (dailyCandles.isNotEmpty()) {
                            val monthlyCandles = dailyCandles
                                .groupBy { candle ->
                                    TimeframeBuckets.bucketStart(
                                        candle.timestamp,
                                        "1M",
                                        instrument.exchange
                                    )
                                }
                                .toSortedMap()
                                .map { (monthStart, rows) ->
                                    val ordered = rows.sortedBy { it.timestamp }
                                    Candle(
                                        symbol = instrument.symbol,
                                        timeframe = "1M",
                                        timestamp = monthStart,
                                        open = ordered.first().open,
                                        high = ordered.maxOf { it.high },
                                        low = ordered.minOf { it.low },
                                        close = ordered.last().close,
                                        volume = ordered.sumOf { it.volume }
                                    )
                                }
                            if (monthlyCandles.isNotEmpty()) {
                                historicalImportMonthlyCandlesSaved += market.saveCandles(
                                    symbol = instrument.symbol,
                                    exchange = instrument.exchange,
                                    timeframe = "1M",
                                    candles = monthlyCandles
                                )
                            }
                        }
                        if (saved.isEmpty() && dailyCandles.isEmpty()) historicalImportSkipped++
                    } catch (error: Exception) {
                        historicalImportErrors++
                        historicalImportLastError =
                            "${instrument.exchange}:${instrument.symbol}: ${error.message ?: "unknown error"}".take(500)
                    } finally {
                        historicalImportCompleted++
                    }
                }
                historicalImportRunning = false
                historicalImportStatus = if (historicalImportErrors == 0) "completed" else "completed_with_errors"
            }
            return historicalImportSnapshot().put("started", true)
        }
    }

    fun historicalImportSnapshot(): JSONObject = JSONObject()
        .put("status", historicalImportStatus)
        .put("running", historicalImportRunning)
        .put("exchange_scope", JSONArray().put("NSE").put("BSE"))
        .put("instrument_scope", "active cash equities and ETFs with verified Kotak tokens")
        .put("timeframe", "1D")
        .put("from_date", "2023-11-09")
        .put("to_date", "2026-11-09")
        .put("total_instruments", historicalImportTotal)
        .put("completed_instruments", historicalImportCompleted)
        .put("remaining_instruments", (historicalImportTotal - historicalImportCompleted).coerceAtLeast(0))
        .put("saved_candles_reported", historicalImportCandlesSaved)
        .put("monthly_candles_saved", historicalImportMonthlyCandlesSaved)
        .put("skipped_instruments", historicalImportSkipped)
        .put("instrument_errors", historicalImportErrors)
        .put("last_error", historicalImportLastError)

    fun disconnectFeed() {
        if (connecting.get()) {
            updateStatus("Live feed connection is still starting")
            return
        }

        executor.execute {
            val service = synchronized(lock) {
                val current = liveFeedService
                liveFeedService = null
                current
            }

            if (service == null) {
                updateStatus("Kotak Live Feed is not connected")
                return@execute
            }

            service.close()
            updateStatus("Kotak Live Feed disconnected")
        }
    }

    fun isFeedConnected(): Boolean {
        val service = synchronized(lock) {
            liveFeedService
        }
        return service?.isConnected() == true
    }

    fun isFeedAuthenticated(): Boolean {
        val service = synchronized(lock) {
            liveFeedService
        }
        return service?.isAuthenticated() == true
    }

    fun liveFeed(): KotakNeoLiveFeedService? = synchronized(lock) {
        liveFeedService
    }

    fun webSocketClient(): KotakNeoWebSocketClient? =
        liveFeed()?.webSocketClient()

    fun isAutoLoginRunning(): Boolean {
        val manager = synchronized(lock) {
            autoLoginManager
        }
        return manager?.isRunning() == true
    }

    fun currentStatus(): String = lastStatus

    fun setStatusListener(listener: ((String) -> Unit)?) {
        statusListener = listener

        if (listener != null) {
            val status = lastStatus
            mainHandler.post {
                statusListener?.invoke(status)
            }
        }
    }

    fun clear() {
        val service: KotakNeoLiveFeedService?
        val manager: KotakAutoLoginManager?

        synchronized(lock) {
            service = liveFeedService
            liveFeedService = null

            manager = autoLoginManager
            autoLoginManager = null
            started = false
        }

        manager?.close()
        service?.close()
        store().clear()

        updateStatus("ERROR | Kotak credentials cleared")
    }

    private fun closeFeed() {
        val service = synchronized(lock) {
            val current = liveFeedService
            liveFeedService = null
            current
        }

        service?.close()
    }

    private fun updateStatus(message: String) {
        lastStatus = message
        Log.d(TAG, message)

        val lower = message.toLowerCase()
        when {
            lower.contains("live ticks") || lower.contains("authenticated") ->
                MarketDataManager.updateConnection(MarketDataManager.ConnectionState.AUTHENTICATED)

            lower.contains("reconnecting") ->
                MarketDataManager.updateConnection(MarketDataManager.ConnectionState.RECONNECTING)

            lower.contains("connecting") ->
                MarketDataManager.updateConnection(MarketDataManager.ConnectionState.CONNECTING)

            lower.contains("disconnected") || lower.contains("closed") ->
                MarketDataManager.updateConnection(MarketDataManager.ConnectionState.DISCONNECTED)

            lower.startsWith("error") ||
                lower.contains("authentication failed") ||
                lower.contains("websocket error") ||
                lower.contains("failed") ->
                MarketDataManager.updateConnection(MarketDataManager.ConnectionState.ERROR)

            lower.contains("connected") ->
                MarketDataManager.updateConnection(MarketDataManager.ConnectionState.CONNECTED)

            else -> {}
        }

        mainHandler.post {
            statusListener?.invoke(message)
        }
    }

    private fun context(): Context {
        return appContext ?: throw IllegalStateException(
            "KotakSessionCoordinator is not initialized"
        )
    }

    private fun store(): KotakNeoSecureStore {
        return secureStore ?: throw IllegalStateException(
            "KotakSessionCoordinator is not initialized"
        )
    }
}
