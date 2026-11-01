package com.sb.backend.services

import android.content.Context
import com.sb.backend.database.BackendDatabase
import com.sb.backend.repositories.InstrumentRepository
import com.sb.backend.repositories.MarketRepository
import com.sb.backend.repositories.SignalRepository
import com.sb.backend.repositories.PaperRepository

class BackendManager(context: Context) {

    private val appContext = context.applicationContext

    val database = BackendDatabase(appContext)
    private val dbHelper = database.databaseHelper

    // Repositories
    val instrumentRepository = InstrumentRepository(dbHelper)
    val marketRepository = MarketRepository(dbHelper)
    val signalRepository = SignalRepository(dbHelper)
    val paperRepository = PaperRepository(dbHelper)

    // Market Data
    val marketDataService = MarketDataService(
        marketRepository,
        instrumentRepository
    )

    // Indicators
    val indicatorService = IndicatorService

    // Signal Engine
    val signalEngine = SignalEngine(
        marketRepository = marketRepository,
        signalRepository = signalRepository
    )

    // Risk Manager
    val riskManager = RiskManager()

    // Paper Trading Engine
    val paperTradingEngine = PaperTradingEngine(
        paperRepository = paperRepository,
        marketRepository = marketRepository,
        riskManager = riskManager
    )

    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize(): Boolean {
        return try {
            if (!database.isDatabaseOpen()) return false

            initialized = true
            true
        } catch (e: Exception) {
            initialized = false
            false
        }
    }

    fun isInitialized(): Boolean {
        return initialized && database.isDatabaseOpen()
    }

    fun getDatabaseStatus(): String {
        return if (isInitialized()) {
            "BACKEND_DATABASE_READY"
        } else {
            "BACKEND_DATABASE_NOT_READY"
        }
    }

    fun close() {
        initialized = false
        database.close()
    }
}
