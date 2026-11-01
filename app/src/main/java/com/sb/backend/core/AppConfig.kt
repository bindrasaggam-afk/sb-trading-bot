package com.sb.backend.core

import android.content.Context

data class BackendConfig(
    val baseUrl: String = AppConfig.DEFAULT_BASE_URL,
    val liveMarketDataEnabled: Boolean = true,
    val requestTimeoutMs: Int = 8_000,
    val readTimeoutMs: Int = 10_000,
    val candlePageSize: Int = 500,
    val shortMarginRate: Double = 0.20,
    val maxOpenPositions: Int = 50,
    val defaultExchange: String = "NSE",
    val defaultTimeframe: String = "15m",
    val signalExpiryMinutes: Int = 60,
    val deviceHeaderName: String = "X-Device-Id"
)

object AppConfig {

    const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"

    private const val PREFS_NAME = "sb_backend_config"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_LIVE_DATA = "live_market_data"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var current: BackendConfig = BackendConfig()

    fun initialize(context: Context) {
        val safeContext = context.applicationContext
        appContext = safeContext
        val prefs = safeContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
        current = current.copy(
            baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL)
                ?: DEFAULT_BASE_URL,
            liveMarketDataEnabled = prefs.getBoolean(KEY_LIVE_DATA, true)
        )
    }

    fun get(): BackendConfig = current

    fun setBaseUrl(url: String) {
        val normalized = url.trim().trimEnd('/')
        require(
            normalized.startsWith("http://") ||
                normalized.startsWith("https://")
        ) {
            "Backend URL must start with http:// or https://"
        }
        current = current.copy(baseUrl = normalized)
        appContext?.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )?.edit()?.putString(KEY_BASE_URL, normalized)?.apply()
    }

    fun setLiveMarketDataEnabled(enabled: Boolean) {
        current = current.copy(liveMarketDataEnabled = enabled)
        appContext?.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )?.edit()?.putBoolean(KEY_LIVE_DATA, enabled)?.apply()
    }

    fun update(config: BackendConfig) {
        require(
            config.baseUrl.startsWith("http://") ||
                config.baseUrl.startsWith("https://")
        ) {
            "Backend URL must start with http:// or https://"
        }
        current = config
    }

    fun reset() {
        current = BackendConfig()
        appContext?.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )?.edit()?.clear()?.apply()
    }
}
