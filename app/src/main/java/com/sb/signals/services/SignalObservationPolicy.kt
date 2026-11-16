package com.sb.signals.services

object SignalObservationPolicy {

    const val WORK_NAME = "signals-periodic-observation"
    const val INTERVAL_MINUTES = 15L
    const val FRESHNESS_MS = 10L * 60L * 1000L

    fun shouldAnalyze(
        lastFinishedAt: Long?,
        now: Long,
        backendReady: Boolean
    ): Boolean {
        if (!backendReady) return false
        if (lastFinishedAt == null) return true
        return now - lastFinishedAt >= FRESHNESS_MS
    }
}
