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
        val maintenanceAcquired = CountDownLatch(1)
        val releaseMaintenance = CountDownLatch(1)
        try {
            val maintenance = executor.submit {
                gate.acquireMaintenance(3, TimeUnit.SECONDS)?.use {
                    maintenanceAcquired.countDown()
                    releaseMaintenance.await(2, TimeUnit.SECONDS)
                }
            }

            operation!!.close()
            assertTrue(maintenanceAcquired.await(2, TimeUnit.SECONDS))
            assertNull(gate.acquireOperation(50, TimeUnit.MILLISECONDS))

            releaseMaintenance.countDown()
            maintenance.get(2, TimeUnit.SECONDS)
            val afterMaintenance = gate.acquireOperation(1, TimeUnit.SECONDS)
            assertNotNull(afterMaintenance)
            afterMaintenance!!.close()
        } finally {
            operation?.close()
            releaseMaintenance.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun scopedOperationAndMaintenanceHelpersRunTheirBlocks() {
        val gate = DatabaseMaintenanceGate()
        var operationRan = false
        var maintenanceRan = false

        gate.withOperation { operationRan = true }
        gate.withMaintenance { maintenanceRan = true }

        assertTrue(operationRan)
        assertTrue(maintenanceRan)
    }

    @Test
    fun operationFailsFastWhenMaintenanceIsHeld() {
        val gate = DatabaseMaintenanceGate()
        gate.withMaintenance {
            try {
                gate.withOperation(timeout = 1, unit = TimeUnit.MILLISECONDS) {
                    throw AssertionError("Operation must not enter during maintenance")
                }
            } catch (expected: DatabaseMaintenanceTimeoutException) {
                assertTrue(expected.message!!.contains("maintenance"))
                return@withMaintenance
            }
            throw AssertionError("Expected maintenance timeout")
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

        val afterTimeout = gate.acquireOperation(1, TimeUnit.SECONDS)
        assertNotNull(afterTimeout)
        afterTimeout!!.close()
    }
}
