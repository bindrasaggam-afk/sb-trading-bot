package com.sb.backend.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sb.backend.core.BackendManager
import com.sb.backend.market.MarketDataManager
import com.sb.backend.security.KotakNeoSecureStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object KotakSessionCoordinator {

    private const val TAG = "KotakSession"

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService by lazy {
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
                        "Tokens updated: ${result.updatedRows}" +
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
                    .getActiveWithTokens(3000)

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
            lower.contains("authenticated") -> MarketDataManager.updateConnection(MarketDataManager.ConnectionState.AUTHENTICATED)
            lower.contains("connected") && !lower.contains("disconnected") -> MarketDataManager.updateConnection(MarketDataManager.ConnectionState.CONNECTED)
            lower.contains("connecting") -> MarketDataManager.updateConnection(MarketDataManager.ConnectionState.CONNECTING)
            lower.contains("reconnecting") -> MarketDataManager.updateConnection(MarketDataManager.ConnectionState.RECONNECTING)
            lower.contains("error") || lower.contains("failed") -> MarketDataManager.updateConnection(MarketDataManager.ConnectionState.ERROR)
            lower.contains("disconnected") || lower.contains("closed") -> MarketDataManager.updateConnection(MarketDataManager.ConnectionState.DISCONNECTED)
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
