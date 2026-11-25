package com.sb.backend.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the market/paper runtime after a normal device reboot.
 * Android does not deliver BOOT_COMPLETED after the user force-stops the app;
 * the user must launch the app again in that case.
 */
class MarketBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            MarketForegroundService.start(context.applicationContext)
        } catch (error: Exception) {
            Log.e(TAG, "Could not restart SB runtime after reboot.", error)
        }
    }

    private companion object {
        const val TAG = "MarketBootReceiver"
    }
}
