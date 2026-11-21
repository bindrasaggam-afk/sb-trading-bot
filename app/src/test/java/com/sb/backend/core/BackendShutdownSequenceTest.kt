package com.sb.backend.core

import com.sb.backend.database.DatabaseMaintenanceGate
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendShutdownSequenceTest {

    @Test
    fun shutdownStopsIncomingThenDrainsProducersThenClosesBothDatabases() {
        val events = mutableListOf<String>()
        val gate = DatabaseMaintenanceGate()
        val sequence = BackendShutdownSequence(
            gate = gate,
            stopProducers = listOf(
                "signals-platform" to { events.add("signals-platform") },
                "kotak-feed" to { events.add("kotak-feed") }
            ),
            closeDatabases = listOf(
                "backend-db" to {
                    events.add("backend-db")
                    assertNull(gate.acquireOperation(0, TimeUnit.SECONDS))
                },
                "signals-db" to {
                    events.add("signals-db")
                    assertNull(gate.acquireOperation(0, TimeUnit.SECONDS))
                }
            )
        )

        val result = sequence.execute { events.add("incoming") }

        assertEquals(
            listOf(
                "incoming",
                "signals-platform",
                "kotak-feed",
                "backend-db",
                "signals-db"
            ),
            events
        )
        assertTrue(result.drained)
        assertTrue(result.errors.isEmpty())
        assertNotNull(gate.acquireOperation(0, TimeUnit.SECONDS))

        val repeated = sequence.execute { events.add("incoming-again") }
        assertSame(result, repeated)
        assertEquals(5, events.size)
    }

    @Test
    fun failingProducerDoesNotBlockOtherSteps() {
        val events = mutableListOf<String>()
        val sequence = BackendShutdownSequence(
            gate = DatabaseMaintenanceGate(),
            stopProducers = listOf(
                "broken" to { throw IllegalStateException("boom") },
                "healthy" to { events.add("healthy") }
            ),
            closeDatabases = listOf(
                "backend-db" to { events.add("backend-db") },
                "signals-db" to { events.add("signals-db") }
            )
        )

        val result = sequence.execute { events.add("incoming") }

        assertEquals(
            listOf("incoming", "healthy", "backend-db", "signals-db"),
            events
        )
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.first().startsWith("broken:"))
        assertTrue(result.drained)
    }

    @Test
    fun gateTimeoutLeavesDatabasesOpen() {
        val events = mutableListOf<String>()
        val gate = DatabaseMaintenanceGate()
        val held = gate.acquireMaintenance(1, TimeUnit.SECONDS)
        assertNotNull(held)
        val sequence = BackendShutdownSequence(
            gate = gate,
            stopProducers = listOf("producer" to { events.add("producer") }),
            closeDatabases = listOf(
                "backend-db" to { events.add("backend-db") },
                "signals-db" to { events.add("signals-db") }
            ),
            timeoutSeconds = 0
        )

        val result = sequence.execute { events.add("incoming") }

        assertEquals(listOf("incoming", "producer"), events)
        assertFalse(result.drained)
        assertEquals(1, result.errors.size)
        held?.close()
    }
}
