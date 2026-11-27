package com.sb.backend.database

import java.util.concurrent.TimeUnit

class DatabaseOperationGuard(
    private val gate: DatabaseMaintenanceGate = DatabaseMaintenanceGate.shared,
    private val timeoutSeconds: Long = DatabaseMaintenanceGate.DEFAULT_TIMEOUT_SECONDS
) {

    fun <T> withOperation(operation: () -> T): T =
        gate.withOperation(timeoutSeconds, TimeUnit.SECONDS, operation)
}
