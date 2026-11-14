package com.sb.backend.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.sb.backend.database.DatabaseBackup
import com.sb.backend.database.SBDatabaseHelper
import com.sb.backend.market.repo.MarketFeedRepository
import com.sb.backend.repositories.InstrumentRepository
import com.sb.backend.repositories.MarketRepository
import com.sb.backend.repositories.PaperRepository
import com.sb.backend.repositories.SignalRepository
import com.sb.backend.security.DeviceIdentity
import com.sb.backend.services.InstrumentMasterImporter
import com.sb.backend.services.KotakNeoLiveFeedService
import com.sb.backend.services.KotakNeoWebSocketClient
import com.sb.backend.services.KotakSessionCoordinator
import com.sb.backend.services.MarketDataService
import com.sb.backend.services.PaperTradingEngine
import com.sb.backend.services.RiskManager
import com.sb.backend.services.SignalEngine
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

object BackendManager {

    private const val TAG = "BackendManager"

    private val lock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var started = false

    @Volatile
    private var ready = false

    private var appContext: Context? = null
    private var executor: ExecutorService? = null

    private lateinit var dbHelper: SBDatabaseHelper
    private lateinit var instrumentRepository: InstrumentRepository
    private lateinit var marketRepository: MarketRepository
    private lateinit var signalRepository: SignalRepository
    private lateinit var paperRepository: PaperRepository
    private lateinit var marketDataService: MarketDataService
    private lateinit var signalEngine: SignalEngine
    private lateinit var paperTradingEngine: PaperTradingEngine
    private lateinit var riskManager: RiskManager

    fun initialize(context: Context) {
        synchronized(lock) {
            if (initialized) return
            val safeContext = context.applicationContext
            AppConfig.initialize(safeContext)
            appContext = safeContext

            dbHelper = SBDatabaseHelper(safeContext)
            instrumentRepository = InstrumentRepository(dbHelper)
            marketRepository = MarketRepository(dbHelper)
            signalRepository = SignalRepository(dbHelper)
            paperRepository = PaperRepository(dbHelper)
            riskManager = RiskManager()
            marketDataService = MarketDataService(
                marketRepository,
                instrumentRepository,
                DeviceIdentity.get(safeContext)
            )
            signalEngine = SignalEngine(
                marketRepository,
                signalRepository
            )
            paperTradingEngine = PaperTradingEngine(
                paperRepository,
                marketRepository,
                riskManager
            )
            executor = Executors.newSingleThreadExecutor()
            initialized = true
        }
    }

    fun start(
        seedBuiltInMasters: Boolean = true,
        onReady: (() -> Unit)? = null
    ) {
        checkInitialized()

        synchronized(lock) {
            if (started) {
                if (ready) {
                    onReady?.invoke()
                }
                return
            }

            val worker = executor ?: run {
                Log.e(TAG, "Backend startup failed: executor is unavailable.")
                return
            }

            started = true

            try {
                worker.execute {
                    try {
                        if (seedBuiltInMasters) {
                            if (instrumentRepository.count() == 0) {
                                instrumentRepository.seedBuiltInMasters()
                            }

                            instrumentRepository.backfillKnownTokens()
                        }

                        val importContext = appContext
                        if (importContext != null) {
                            val importResult = InstrumentMasterImporter(
                                context = importContext,
                                databaseHelper = dbHelper,
                                instrumentRepository = instrumentRepository
                            ).importIfNeeded()

                            importResult.errors.take(5).forEach { error ->
                                Log.w(TAG, "Instrument master import: $error")
                            }

                            Log.i(
                                TAG,
                                "Instrument master import: rows=${importResult.rowCount}, " +
                                    "saved=${importResult.savedCount}, " +
                                    "skipped=${importResult.skippedCount}, " +
                                    "changed=${importResult.importedNow}, " +
                                    "categories=${importResult.categoryCounts}"
                            )
                        }

                        signalRepository.expireOld()

                        synchronized(lock) {
                            if (!initialized) {
                                Log.w(TAG, "Backend stopped before startup completed.")
                                return@execute
                            }

                            ready = true
                        }

                        Log.i(TAG, "Backend initialization completed successfully.")

                        try {
                            onReady?.invoke()
                        } catch (callbackError: Exception) {
                            Log.e(
                                TAG,
                                "Backend ready callback failed.",
                                callbackError
                            )
                        }

                    } catch (error: Exception) {
                        synchronized(lock) {
                            ready = false
                            started = false
                        }

                        Log.e(
                            TAG,
                            "Backend initialization failed.",
                            error
                        )
                    }
                }
            } catch (error: RejectedExecutionException) {
                started = false
                ready = false

                Log.e(
                    TAG,
                    "Backend startup rejected by executor.",
                    error
                )
            }
        }
    }

    fun isInitialized(): Boolean = initialized

    fun isStarted(): Boolean = started

    fun isReady(): Boolean = ready

    fun getConfig(): BackendConfig = AppConfig.get()

    fun setBaseUrl(url: String) {
        AppConfig.setBaseUrl(url)
    }

    fun deviceId(): String {
        val context = appContext ?: throw IllegalStateException(
            "BackendManager is not initialized."
        )
        return DeviceIdentity.get(context)
    }

    fun database(): SBDatabaseHelper {
        checkInitialized()
        return dbHelper
    }

    fun instruments(): InstrumentRepository {
        checkInitialized()
        return instrumentRepository
    }

    fun market(): MarketRepository {
        checkInitialized()
        return marketRepository
    }

    fun signals(): SignalRepository {
        checkInitialized()
        return signalRepository
    }

    fun paper(): PaperRepository {
        checkInitialized()
        return paperRepository
    }

    fun marketData(): MarketDataService {
        checkInitialized()
        return marketDataService
    }

    fun marketFeed(): MarketFeedRepository = MarketFeedRepository

    fun session(): KotakSessionCoordinator = KotakSessionCoordinator

    fun liveFeed(): KotakNeoLiveFeedService? =
        KotakSessionCoordinator.liveFeed()

    fun webSocket(): KotakNeoWebSocketClient? =
        KotakSessionCoordinator.webSocketClient()

    fun signalEngine(): SignalEngine {
        checkInitialized()
        return signalEngine
    }

    fun paperEngine(): PaperTradingEngine {
        checkInitialized()
        return paperTradingEngine
    }

    fun risk(): RiskManager {
        checkInitialized()
        return riskManager
    }

    fun backup(directoryPrefix: String = "sb_backend"): File? {
        val context = appContext ?: return null
        return DatabaseBackup(context, dbHelper).backup(directoryPrefix)
    }

    fun listBackups(): List<File> {
        val context = appContext ?: return emptyList()
        return DatabaseBackup(context, dbHelper).listBackups()
    }

    fun exportDatabaseToDownloads(): Uri? {
        val context = appContext ?: return null

        return try {
            // Existing database backup system का उपयोग
            val backupFile = backup("sb_backend_export") ?: return null

            if (!backupFile.exists()) return null

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return null
            }

            val values = ContentValues().apply {
                put(
                    MediaStore.Downloads.DISPLAY_NAME,
                    "sb_backend.db"
                )
                put(
                    MediaStore.Downloads.MIME_TYPE,
                    "application/x-sqlite3"
                )
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "Download"
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: return null

            try {
                resolver.openOutputStream(uri)?.use { output ->
                    backupFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Cannot open Download output stream")

                val completed = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }

                resolver.update(uri, completed, null, null)

                uri
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        } catch (error: Exception) {
            error.printStackTrace()
            null
        }
    }

    /**
     * Exports only historical candle rows to a CSV in the public Downloads folder.
     * The database is queried read-only; no candle or account rows are modified.
     * Returns the saved Uri and exported row count.
     */
    fun exportCandlesCsvToDownloads(): Pair<Uri, Int>? {
        val context = appContext ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        var outputUri: Uri? = null
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(
                    MediaStore.Downloads.DISPLAY_NAME,
                    "sb_historical_candles_${System.currentTimeMillis()}.csv"
                )
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val uri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IOException("Cannot create CSV in Downloads")
            outputUri = uri

            var exported = 0
            resolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                    writer.write("symbol,exchange,timeframe,timestamp,open,high,low,close,volume,candle_status\n")
                    database().readableDatabase.rawQuery(
                        """
                        SELECT symbol, exchange, timeframe, timestamp,
                               open, high, low, close, volume, status
                        FROM ${SBDatabaseHelper.TABLE_CANDLES}
                        ORDER BY symbol ASC, exchange ASC, timeframe ASC, timestamp ASC
                        """.trimIndent(),
                        null
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val fields = (0 until cursor.columnCount).map { index ->
                                if (cursor.isNull(index)) "" else cursor.getString(index)
                            }
                            writer.write(fields.joinToString(",") { csvEscape(it) })
                            writer.write("\n")
                            exported++
                        }
                    }
                    writer.flush()
                }
            } ?: throw IOException("Cannot open CSV output stream")

            if (exported == 0) {
                resolver.delete(uri, null, null)
                outputUri = null
                null
            } else {
                val completed = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                check(resolver.update(uri, completed, null, null) == 1) {
                    "Could not finalize CSV in Downloads"
                }
                Pair(uri, exported)
            }
        } catch (error: Exception) {
            outputUri?.let { uri ->
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (_: Exception) {
                    // Keep the original export error.
                }
            }
            Log.e(TAG, "Historical candle CSV export failed", error)
            null
        }
    }

    private fun csvEscape(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (
            escaped.contains(",") ||
            escaped.contains("\"") ||
            escaped.contains("\n") ||
            escaped.contains("\r")
        ) {
            "\"$escaped\""
        } else {
            escaped
        }
    }

    fun restore(file: File): Boolean {
        synchronized(lock) {
            val context = appContext ?: return false
            dbHelper.close()
            return DatabaseBackup(context).restore(file)
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!initialized) return
            executor?.shutdown()
            executor = null
            marketDataService.shutdown()
            dbHelper.close()
            initialized = false
            started = false
            ready = false
            appContext = null
        }
    }

    private fun checkInitialized(): Context {
        if (!initialized) {
            throw IllegalStateException(
                "Call BackendManager.initialize(context) first."
            )
        }
        return appContext ?: throw IllegalStateException(
            "BackendManager context is unavailable."
        )
    }
}
