package com.sb.backend.database

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Fair, process-local gate for coordinating database operations with exclusive maintenance.
 *
 * Every database writer/reader that must be drained has to acquire an OperationLease.
 * Maintenance is safe only after all relevant call sites have adopted this gate.
 */
class DatabaseMaintenanceGate {
    private val lock = ReentrantLock(true)
    private val changed = lock.newCondition()
    private var maintenanceRequested = false
    private var activeOperations = 0

    fun acquireOperation(timeout: Long, unit: TimeUnit): OperationLease? {
        var remaining = unit.toNanos(timeout)
        lock.lockInterruptibly()
        try {
            while (maintenanceRequested) {
                if (remaining <= 0L) return null
                remaining = changed.awaitNanos(remaining)
            }
            activeOperations++
            return OperationLease(this)
        } finally {
            lock.unlock()
        }
    }

    fun acquireMaintenance(timeout: Long, unit: TimeUnit): MaintenanceLease? {
        var remaining = unit.toNanos(timeout)
        lock.lockInterruptibly()
        var ownsRequest = false
        try {
            while (maintenanceRequested) {
                if (remaining <= 0L) return null
                remaining = changed.awaitNanos(remaining)
            }

            maintenanceRequested = true
            ownsRequest = true

            while (activeOperations > 0) {
                if (remaining <= 0L) {
                    maintenanceRequested = false
                    ownsRequest = false
                    changed.signalAll()
                    return null
                }
                remaining = changed.awaitNanos(remaining)
            }

            ownsRequest = false
            return MaintenanceLease(this)
        } catch (interrupted: InterruptedException) {
            if (ownsRequest) {
                maintenanceRequested = false
                changed.signalAll()
            }
            throw interrupted
        } finally {
            lock.unlock()
        }
    }

    private fun releaseOperation() {
        lock.lock()
        try {
            check(activeOperations > 0) { "No active database operation to release" }
            activeOperations--
            if (activeOperations == 0) changed.signalAll()
        } finally {
            lock.unlock()
        }
    }

    private fun releaseMaintenance() {
        lock.lock()
        try {
            check(maintenanceRequested) { "Database maintenance is not active" }
            maintenanceRequested = false
            changed.signalAll()
        } finally {
            lock.unlock()
        }
    }

    class OperationLease internal constructor(
        private val owner: DatabaseMaintenanceGate
    ) : AutoCloseable {
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) owner.releaseOperation()
        }
    }

    class MaintenanceLease internal constructor(
        private val owner: DatabaseMaintenanceGate
    ) : AutoCloseable {
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) owner.releaseMaintenance()
        }
    }
}
