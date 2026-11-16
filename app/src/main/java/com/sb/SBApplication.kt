package com.sb

import android.app.Application
import android.util.Log
import com.sb.signals.services.SignalsPlatform

class SBApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        SignalsPlatform.scheduleBackgroundObservation(this)

        val originalHandler =
            Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(
                "SB_CRASH",
                "Uncaught exception in thread ${thread.name}",
                throwable
            )

            originalHandler?.uncaughtException(thread, throwable)
        }
    }
}
