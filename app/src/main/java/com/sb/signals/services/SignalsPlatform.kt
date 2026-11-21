package com.sb.signals.services

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.sb.backend.core.BackendManager
import com.sb.backend.database.DatabaseMaintenanceGate
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
    private val analysisLock = Any()

    @Volatile
    private var appContext: Context? = null

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

    @Volatile
    private var accepting: Boolean = true

    fun initialize(context: Context) {
        ensureInitialized(context)
    }

    fun repository(context: Context): SignalRepository {
        ensureInitialized(context)
        return signalRepository
            ?: throw IllegalStateException("Signals repository unavailable")
    }

    fun database(context: Context): SignalDatabase {
        ensureInitialized(context)
        return database
            ?: throw IllegalStateException("Signals database unavailable")
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
        accepting = true
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
                    runAnalysis()
                } catch (error: Exception) {
                    Log.e(TAG, "Scheduled signal analysis failed", error)
                }
            },
            INITIAL_DELAY_SECONDS,
            SCAN_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
        scheduleBackgroundObservation(context)
        true
    }

    fun stop() = synchronized(lock) {
        accepting = false
        running = false
        val currentScheduler = scheduler
        val currentExecutor = executor
        scheduler = null
        executor = null
        analysisService = null
        currentScheduler?.shutdownNow()
        currentExecutor?.shutdownNow()
        awaitTermination(currentScheduler)
        awaitTermination(currentExecutor)
    }

    fun closeDatabase() {
        synchronized(lock) {
            if (running) return
            try {
                database?.close()
            } finally {
                database = null
                signalRepository = null
                analysisService = null
                appContext = null
            }
        }
    }

    private fun awaitTermination(service: ExecutorService?) {
        if (service == null) return
        try {
            if (!service.awaitTermination(5, TimeUnit.SECONDS)) {
                service.shutdownNow()
            }
        } catch (_: InterruptedException) {
            service.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    fun isRunning(): Boolean = running

    fun analyzeNow(
        context: Context,
        onComplete: (AnalysisSummary?) -> Unit = {}
    ): Boolean {
        if (!accepting) {
            onComplete(null)
            return false
        }
        if (!start(context)) {
            onComplete(null)
            return false
        }
        val worker = executor
        if (worker == null || worker.isShutdown) {
            onComplete(null)
            return false
        }
        if (analysisService == null) {
            onComplete(null)
            return false
        }
        return try {
            worker.execute {
                val summary = runAnalysis()
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

    fun scheduleBackgroundObservation(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequest.Builder(
                SignalObservationWorker::class.java,
                SignalObservationPolicy.INTERVAL_MINUTES,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15L,
                    TimeUnit.MINUTES
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    SignalObservationPolicy.WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        } catch (error: Exception) {
            Log.e(TAG, "Background observation scheduling failed", error)
        }
    }

    fun runBackgroundObservation(context: Context): Boolean {
        ensureInitialized(context)
        val now = System.currentTimeMillis()
        val lastFinishedAt = try {
            signalRepository?.getLastRun()?.finishedAt
        } catch (error: Exception) {
            Log.e(TAG, "Last signal run query failed", error)
            null
        }
        if (!SignalObservationPolicy.shouldAnalyze(
                lastFinishedAt,
                now,
                BackendManager.isInitialized()
            )
        ) {
            return true
        }
        return runAnalysis() != null
    }

    private fun runAnalysis(): AnalysisSummary? {
        if (!accepting) return null
        return try {
            DatabaseMaintenanceGate.shared.withOperation {
                runAnalysisGuarded()
            }
        } catch (error: Exception) {
            Log.e(TAG, "Signal analysis gate unavailable", error)
            null
        }
    }

    private fun runAnalysisGuarded(): AnalysisSummary? {
        val service = synchronized(lock) {
            analysisService
        } ?: return null
        if (!accepting) return null
        return synchronized(analysisLock) {
            val summary = try {
                service.seedStrategyVersions()
                service.run()
            } catch (error: Exception) {
                Log.e(TAG, "Signal analysis failed", error)
                null
            }
            if (summary != null) {
                val context = appContext
                if (context != null &&
                    BackendManager.isInitialized() &&
                    BackendManager.isReady()
                ) {
                    try {
                        val result =
                            PaperSignalBridge.forwardOpenSignals(context)
                        Log.d(
                            TAG,
                            "Paper signal bridge ${result.summary()}"
                        )
                    } catch (error: Exception) {
                        Log.e(
                            TAG,
                            "Paper signal forwarding failed",
                            error
                        )
                    }
                }
            }
            summary
        }
    }

    fun execute(context: Context, task: () -> Unit): Boolean {
        if (!accepting) return false
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
        appContext = context.applicationContext
        if (database != null && signalRepository != null) return
        val db = SignalDatabase(context.applicationContext)
        val repo = SignalRepository(db)
        database = db
        signalRepository = repo
        analysisService = SignalAnalysisService(repo)
    }
}
