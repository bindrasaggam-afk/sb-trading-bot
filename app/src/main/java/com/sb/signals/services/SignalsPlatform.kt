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
        val service = synchronized(lock) {
            analysisService
        } ?: return null
        return synchronized(analysisLock) {
            try {
                service.seedStrategyVersions()
                service.run()
            } catch (error: Exception) {
                Log.e(TAG, "Signal analysis failed", error)
                null
            }
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
