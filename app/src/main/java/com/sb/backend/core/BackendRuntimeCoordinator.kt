package com.sb.backend.core

import android.content.Context
import com.sb.backend.database.DatabaseMaintenanceGate
import com.sb.backend.market.MarketDataManager
import com.sb.backend.services.KotakSessionCoordinator
import com.sb.backend.services.MarketRuntimeService
import com.sb.backend.services.SignalScanCoordinator
import com.sb.signals.services.SignalsPlatform

object BackendRuntimeCoordinator {

    private val lifecycleLock = Any()

    @Volatile
    private var stopping = false

    @Volatile
    private var sequence: BackendShutdownSequence? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        BackendManager.initialize(appContext)
        SignalsPlatform.initialize(appContext)

        synchronized(lifecycleLock) {
            stopping = false
            if (sequence == null || sequence?.isFinished() == true) {
                sequence = newSequence()
            }
        }
    }

    fun start(
        context: Context,
        onReady: () -> Boolean,
        onError: (Throwable) -> Unit
    ) {
        if (stopping) {
            onError(IllegalStateException("Backend runtime is shutting down."))
            return
        }

        val appContext = context.applicationContext
        initialize(appContext)

        BackendManager.start(
            seedBuiltInMasters = true,
            onReady = readyCallback@ {
                if (stopping || !onReady()) return@readyCallback
                try {
                    KotakSessionCoordinator.initialize(appContext)
                    MarketRuntimeService.start()
                    SignalsPlatform.start(appContext)
                    KotakSessionCoordinator.startIfEnabled()
                } catch (error: Exception) {
                    onError(error)
                }
            },
            onError = onError
        )
    }

    fun shutdown(stopIncoming: () -> Unit): ShutdownResult? {
        val current = synchronized(lifecycleLock) {
            stopping = true
            sequence
        }
        if (current == null) {
            stopIncoming()
            return null
        }
        val result = current.execute(stopIncoming)
        synchronized(lifecycleLock) {
            sequence = null
        }
        return result
    }

    private fun newSequence(): BackendShutdownSequence =
        BackendShutdownSequence(
            gate = DatabaseMaintenanceGate.shared,
            stopProducers = listOf(
                "signals-platform" to { SignalsPlatform.stop() },
                "kotak-feed" to { KotakSessionCoordinator.shutdown() },
                "signal-scanner" to { SignalScanCoordinator.shutdown() },
                "market-runtime" to { MarketRuntimeService.shutdown() },
                "market-snapshots" to { MarketDataManager.shutdown() }
            ),
            closeDatabases = listOf(
                "backend-db" to { BackendManager.stop() },
                "signals-db" to { SignalsPlatform.closeDatabase() }
            )
        )
}
