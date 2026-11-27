package com.sb.backend.core

import com.sb.backend.database.DatabaseMaintenanceGate
import java.util.concurrent.TimeUnit

data class ShutdownResult(
    val completedSteps: List<String>,
    val errors: List<String>,
    val drained: Boolean
)

class BackendShutdownSequence(
    private val gate: DatabaseMaintenanceGate,
    private val stopProducers: List<Pair<String, () -> Unit>>,
    private val closeDatabases: List<Pair<String, () -> Unit>>,
    private val timeoutSeconds: Long = 10L
) {

    private val lock = Any()

    @Volatile
    private var result: ShutdownResult? = null

    @Volatile
    private var running = false

    fun isFinished(): Boolean = result != null

    fun execute(stopIncoming: () -> Unit): ShutdownResult = synchronized(lock) {
        val existing = result
        if (existing != null) return@synchronized existing
        if (running) {
            return@synchronized ShutdownResult(
                emptyList(),
                listOf("shutdown already in progress"),
                false
            )
        }
        running = true

        val completed = mutableListOf<String>()
        val errors = mutableListOf<String>()

        fun runStep(name: String, action: () -> Unit) {
            try {
                action()
                completed.add(name)
            } catch (error: Throwable) {
                errors.add(
                    "$name: ${error.message ?: error.javaClass.simpleName}"
                )
            }
        }

        try {
            runStep("stop-incoming", stopIncoming)
            stopProducers.forEach { (name, action) -> runStep(name, action) }

            val lease = try {
                gate.acquireMaintenance(timeoutSeconds, TimeUnit.SECONDS)
            } catch (error: Exception) {
                errors.add(
                    "maintenance-gate: " +
                        (error.message ?: error.javaClass.simpleName)
                )
                null
            }

            if (lease == null) {
                errors.add("maintenance-gate: timed out")
            } else {
                lease.use {
                    closeDatabases.forEach { (name, action) ->
                        runStep(name, action)
                    }
                }
            }

            ShutdownResult(
                completed.toList(),
                errors.toList(),
                lease != null
            ).also { result = it }
        } finally {
            running = false
        }
    }
}
