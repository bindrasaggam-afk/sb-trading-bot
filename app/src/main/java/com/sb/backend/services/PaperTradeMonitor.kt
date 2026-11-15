package com.sb.backend.services

import android.util.Log
import com.sb.backend.core.AppConfig
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class PaperTradeMonitor(
    private val paperEngine: PaperTradingEngine,
    private val intervalMs: Long = AppConfig.get().paperMonitorIntervalMs
) {

    private val lock = Any()

    @Volatile
    private var executor: ScheduledExecutorService? = null

    fun start() {
        synchronized(lock) {
            if (executor != null) return

            val safeInterval = intervalMs.coerceAtLeast(1_000L)
            val scheduled = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "paper-trade-monitor").apply {
                    isDaemon = true
                }
            }
            executor = scheduled
            scheduled.scheduleWithFixedDelay(
                { runSafely() },
                safeInterval,
                safeInterval,
                TimeUnit.MILLISECONDS
            )
        }
    }

    fun stop() {
        val scheduled = synchronized(lock) {
            val current = executor
            executor = null
            current
        } ?: return

        scheduled.shutdown()
        try {
            if (!scheduled.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduled.shutdownNow()
            }
        } catch (_: InterruptedException) {
            scheduled.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private fun runSafely() {
        try {
            paperEngine.monitorOpenTrades()
        } catch (error: Exception) {
            Log.e(TAG, "Paper trade monitor cycle failed.", error)
        }
    }

    private companion object {
        private const val TAG = "PaperTradeMonitor"
    }
}
