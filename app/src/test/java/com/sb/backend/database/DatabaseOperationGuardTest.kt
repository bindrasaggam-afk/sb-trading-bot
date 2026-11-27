package com.sb.backend.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DatabaseOperationGuardTest {

    @Test
    fun runsOperationAndReleasesLease() {
        val gate = DatabaseMaintenanceGate()
        val guard = DatabaseOperationGuard(gate)
        var calls = 0

        val result = guard.withOperation {
            calls++
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(1, calls)
        gate.acquireMaintenance(1, TimeUnit.SECONDS).use { lease ->
            assertNotNull(lease)
        }
    }

    @Test
    fun failsFastWhileMaintenanceHeld() {
        val gate = DatabaseMaintenanceGate()
        val guard = DatabaseOperationGuard(gate, timeoutSeconds = 0)

        gate.acquireMaintenance(1, TimeUnit.SECONDS).use {
            assertThrows(DatabaseMaintenanceTimeoutException::class.java) {
                guard.withOperation { }
            }
        }
    }

    @Test
    fun nestedOperationsReenterTheSameGate() {
        val gate = DatabaseMaintenanceGate()
        val guard = DatabaseOperationGuard(gate)
        val visited = ArrayList<String>()

        guard.withOperation {
            visited.add("outer")
            guard.withOperation {
                visited.add("inner")
            }
        }

        assertEquals(listOf("outer", "inner"), visited)
        gate.acquireMaintenance(1, TimeUnit.SECONDS).use { lease ->
            assertNotNull(lease)
        }
    }

    @Test
    fun releasesLeaseWhenOperationThrows() {
        val gate = DatabaseMaintenanceGate()
        val guard = DatabaseOperationGuard(gate)

        assertThrows(IllegalStateException::class.java) {
            guard.withOperation { throw IllegalStateException("boom") }
        }

        gate.acquireMaintenance(1, TimeUnit.SECONDS).use { lease ->
            assertNotNull(lease)
        }
    }

    @Test
    fun maintenanceWaitsForActiveGuardOperation() {
        val gate = DatabaseMaintenanceGate()
        val guard = DatabaseOperationGuard(gate)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        val worker = Thread {
            guard.withOperation {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
        }
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNull(gate.acquireMaintenance(0, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            worker.join(2_000L)
        }

        gate.acquireMaintenance(1, TimeUnit.SECONDS).use { lease ->
            assertNotNull(lease)
        }
    }
}
