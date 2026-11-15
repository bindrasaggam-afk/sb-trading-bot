package com.sb.signals.services

import android.content.Context
import android.util.Log
import com.sb.signals.data.SignalDatabase
import com.sb.signals.data.SignalRepository
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

object SignalsPlatform {

    private const val TAG = "SignalsPlatform"
    private const val SCAN_INTERVAL_SECONDS = 300L
    private const val INITIAL_DELAY_SECONDS = 15L

    private val lock = Any()

    @Volatile
    private var database: SignalDatabase? = null

    @Volatile
    private var signalRepository: SignalRepository? = null

    @Volatile
    private var analysisService: SignalAnalysisService? = null

    @Volatile
    private var executor: ExecutorService? = null

    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    private var running: Boolean = false

    fun repository(context: Context): SignalRepository {
        ensureInitialized(context)
        return signalRepository
            ?: throw IllegalStateException("Signals repository unavailable")
    }

    fun start(context: Context): Boolean = synchronized(lock) {
        ensureInitializedLocked(context)
        if (running) return true
        if (analysisService == null) {
            analysisService = SignalAnalysisService(
                signalRepository
                    ?: throw IllegalStateException(
                        "Signals repository unavailable"
                    )
            )
        }

        val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "signals-analysis").apply {
                isDaemon = true
            }
        }
        val scanScheduler = Executors.newSingleThreadScheduledExecutor {
            runnable ->
            Thread(runnable, "signals-scheduler").apply {
                isDaemon = true
            }
        }

        executor = worker
        scheduler = scanScheduler
        running = true

        worker.execute {
            try {
                analysisService?.seedStrategyVersions()
            } catch (error: Exception) {
                Log.e(TAG, "Strategy version seeding failed", error)
            }
        }

        scanScheduler.scheduleWithFixedDelay(
            {
                try {
                    analysisService?.run()
                } catch (error: Exception) {
                    Log.e(TAG, "Scheduled signal analysis failed", error)
                }
            },
            INITIAL_DELAY_SECONDS,
            SCAN_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
        true
    }

    fun stop() = synchronized(lock) {
        running = false
        scheduler?.shutdownNow()
        executor?.shutdownNow()
        scheduler = null
        executor = null
        analysisService = null
    }

    fun isRunning(): Boolean = running

    fun analyzeNow(
        context: Context,
        onComplete: (AnalysisSummary?) -> Unit = {}
    ): Boolean {
        if (!start(context)) {
            onComplete(null)
            return false
        }
        val worker = executor
        if (worker == null || worker.isShutdown) {
            onComplete(null)
            return false
        }
        val service = analysisService
        if (service == null) {
            onComplete(null)
            return false
        }
        return try {
            worker.execute {
                val summary = try {
                    service.run()
                } catch (error: Exception) {
                    Log.e(TAG, "Manual signal analysis failed", error)
                    null
                }
                try {
                    onComplete(summary)
                } catch (error: Exception) {
                    Log.e(TAG, "Signal analysis callback failed", error)
                }
            }
            true
        } catch (_: Exception) {
            onComplete(null)
            false
        }
    }

    fun execute(context: Context, task: () -> Unit): Boolean {
        ensureInitialized(context)
        val worker = executor
        if (worker == null || worker.isShutdown) {
            return false
        }
        return try {
            worker.execute(task)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun analysisService(): SignalAnalysisService? = analysisService

    private fun ensureInitialized(context: Context) {
        if (signalRepository == null) {
            synchronized(lock) {
                ensureInitializedLocked(context)
            }
        }
    }

    private fun ensureInitializedLocked(context: Context) {
        if (database != null && signalRepository != null) return
        val db = SignalDatabase(context.applicationContext)
        val repo = SignalRepository(db)
        database = db
        signalRepository = repo
        analysisService = SignalAnalysisService(repo)
    }
}
