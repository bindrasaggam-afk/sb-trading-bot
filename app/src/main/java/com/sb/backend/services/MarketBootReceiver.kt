package com.sb.backend.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the persistent market runtime after a normal device reboot.
 * Android force-stop still requires the user to launch the app again.
 */
class MarketBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            MarketForegroundService.start(context.applicationContext)
        } catch (error: Exception) {
            Log.e(TAG, "Could not restart market runtime after reboot", error)
        }
    }

    private companion object {
        private const val TAG = "SB_MARKET_BOOT"
    }
}
