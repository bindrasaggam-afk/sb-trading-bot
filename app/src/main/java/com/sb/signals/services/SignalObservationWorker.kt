package com.sb.signals.services

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

class SignalObservationWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            if (SignalsPlatform.runBackgroundObservation(applicationContext)) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (error: Exception) {
            Log.e(TAG, "Background signal observation failed", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SignalObservation"
    }
}
