package com.sb.backend.database

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DatabaseMaintenanceGateTest {

    @Test
    fun maintenanceWaitsForActiveOperationAndBlocksNewOperations() {
        val gate = DatabaseMaintenanceGate()
        val operation = gate.acquireOperation(1, TimeUnit.SECONDS)
        assertNotNull(operation)

        val executor = Executors.newSingleThreadExecutor()
        val maintenanceStarted = CountDownLatch(1)
        val releaseMaintenance = CountDownLatch(1)
        try {
            val maintenance = executor.submit {
                maintenanceStarted.countDown()
                gate.acquireMaintenance(3, TimeUnit.SECONDS)?.use {
                    releaseMaintenance.await(2, TimeUnit.SECONDS)
                }
            }

            assertTrue(maintenanceStarted.await(1, TimeUnit.SECONDS))
            assertNull(gate.acquireOperation(100, TimeUnit.MILLISECONDS))

            operation!!.close()
            // The maintenance lease is now held by the worker; new operations stay blocked.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            var blocked = true
            while (System.nanoTime() < deadline) {
                if (gate.acquireOperation(20, TimeUnit.MILLISECONDS) != null) {
                    blocked = false
                    break
                }
            }
            assertTrue(blocked)

            releaseMaintenance.countDown()
            maintenance.get(2, TimeUnit.SECONDS)
            assertNotNull(gate.acquireOperation(1, TimeUnit.SECONDS))
        } finally {
            operation?.close()
            releaseMaintenance.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun maintenanceTimeoutReopensAdmission() {
        val gate = DatabaseMaintenanceGate()
        val operation = gate.acquireOperation(1, TimeUnit.SECONDS)
        assertNotNull(operation)
        try {
            assertNull(gate.acquireMaintenance(50, TimeUnit.MILLISECONDS))
        } finally {
            operation!!.close()
        }

        assertNotNull(gate.acquireOperation(1, TimeUnit.SECONDS))
    }
}
