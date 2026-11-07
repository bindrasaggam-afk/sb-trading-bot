
package com.sb.backend.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sb.backend.core.BackendManager
import com.sb.backend.server.EmbeddedApiServer

class MarketForegroundService : Service() {

    companion object {
        private const val TAG = "SB_MARKET_FOREGROUND"

        const val ACTION_START =
            "com.sb.action.START_MARKET_RUNTIME"

        private const val CHANNEL_ID =
            "sb_market_runtime_channel"

        private const val NOTIFICATION_ID = 7101

        @Volatile
        private var runtimeStarted = false

        fun start(context: Context) {
            val appContext = context.applicationContext

            val intent = Intent(
                appContext,
                MarketForegroundService::class.java
            ).apply {
                action = ACTION_START
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Unable to start service", error)
            }
        }

        private fun resetRuntimeState() {
            synchronized(MarketForegroundService::class.java) {
                runtimeStarted = false
            }
        }
    }

    // API server is owned by this foreground service.
    private var embeddedApiServer: EmbeddedApiServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Foreground service created")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        try {
            startForeground(
                NOTIFICATION_ID,
                createNotification()
            )

            if (intent == null || intent.action == ACTION_START) {
                startBackendRuntime()
            }

        } catch (error: Exception) {
            Log.e(TAG, "Service startup failed", error)
            stopSelf(startId)
        }

        return START_STICKY
    }

    private fun startBackendRuntime() {

        synchronized(MarketForegroundService::class.java) {
            if (runtimeStarted) {
                Log.d(TAG, "Runtime already started")
                return
            }
            runtimeStarted = true
        }

        try {
            // Start local API server on port 10000.
            startApiServer()

            // Initialize the main backend.
            BackendManager.initialize(applicationContext)

            BackendManager.start(
                seedBuiltInMasters = true
            ) {
                try {
                    KotakSessionCoordinator.initialize(
                        applicationContext
                    )

                    KotakSessionCoordinator.startIfEnabled()

                    Log.d(
                        TAG,
                        "Backend ready; Kotak feed startup requested"
                    )

                } catch (error: Exception) {
                    Log.e(TAG, "Kotak feed startup failed", error)
                }
            }

        } catch (error: Exception) {
            Log.e(TAG, "Backend initialization failed", error)

            synchronized(MarketForegroundService::class.java) {
                runtimeStarted = false
            }
        }
    }

    private fun startApiServer() {
        try {
            if (embeddedApiServer?.isRunning() == true) {
                Log.d(TAG, "API server already running")
                return
            }

            val server = EmbeddedApiServer(10000)
            embeddedApiServer = server

            val started = server.start()

            Log.d(
                TAG,
                "Embedded API Server Started: $started"
            )

        } catch (error: Exception) {
            Log.e(TAG, "API server startup failed", error)
        }
    }

    private fun stopApiServer() {
        try {
            embeddedApiServer?.stop()
            embeddedApiServer = null

            Log.d(TAG, "Embedded API Server stopped")

        } catch (error: Exception) {
            Log.e(TAG, "API server stop failed", error)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SB Market Runtime",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Keeps SB backend and Kotak market feed running"
                setShowBadge(false)
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("SB Market Service")
            .setContentText(
                "Backend, API server and market feed are active"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "App task removed; service remains active")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.w(TAG, "Foreground service destroyed")

        stopApiServer()
        resetRuntimeState()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
