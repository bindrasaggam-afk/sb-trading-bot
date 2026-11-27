package com.sb.signals.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalObservationPolicyTest {

    private val now = 1_000_000_000L

    @Test
    fun skipsWhenBackendIsNotReady() {
        assertFalse(
            SignalObservationPolicy.shouldAnalyze(
                lastFinishedAt = null,
                now = now,
                backendReady = false
            )
        )
    }

    @Test
    fun runsWhenNoPreviousRunExists() {
        assertTrue(
            SignalObservationPolicy.shouldAnalyze(
                lastFinishedAt = null,
                now = now,
                backendReady = true
            )
        )
    }

    @Test
    fun skipsRecentlyCompletedRuns() {
        assertFalse(
            SignalObservationPolicy.shouldAnalyze(
                lastFinishedAt = now - 60_000L,
                now = now,
                backendReady = true
            )
        )
    }

    @Test
    fun runsWhenPreviousRunIsStale() {
        assertTrue(
            SignalObservationPolicy.shouldAnalyze(
                lastFinishedAt = now -
                    SignalObservationPolicy.FRESHNESS_MS - 1L,
                now = now,
                backendReady = true
            )
        )
    }
}
