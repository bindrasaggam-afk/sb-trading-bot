package com.sb.backend.database

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatabaseFileBackupTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun inspector(
        spec: DatabaseSpec,
        corruptFileName: String? = null
    ): DatabaseFileInspector = object : DatabaseFileInspector {
        override fun integrityCheck(file: File): String? =
            if (file.name == corruptFileName) "corrupt" else "ok"

        override fun userVersion(file: File): Int? =
            if (file.name == corruptFileName) {
                spec.expectedUserVersion
            } else {
                spec.expectedUserVersion
            }

        override fun tableNames(file: File): Set<String> = spec.requiredTables
    }

    private fun backup(
        spec: DatabaseSpec,
        databaseFile: File,
        backupDirectory: File,
        gate: DatabaseMaintenanceGate,
        inspector: DatabaseFileInspector,
        checkpoint: () -> CheckpointResult? = { null },
        onBeforeReplace: () -> Unit = {}
    ): DatabaseFileBackup = DatabaseFileBackup(
        databaseFile = databaseFile,
        backupDirectory = backupDirectory,
        spec = spec,
        gate = gate,
        inspector = inspector,
        checkpoint = checkpoint,
        onBeforeReplace = onBeforeReplace,
        nowMillis = { 1000L },
        maintenanceTimeoutSeconds = 1
    )

    @Test
    fun backupThenRestoreKeepsOwnerScopeAndReplacesContent() {
        val db = temporaryFolder.newFile("sb_backend.db")
        db.writeText("ORIGINAL")
        val backupDir = temporaryFolder.newFolder("backend_backups")
        val candidate = temporaryFolder.newFile("candidate.db")
        candidate.writeText("RESTORED")
        val gate = DatabaseMaintenanceGate()
        val service = backup(
            DatabaseSpecs.BACKEND,
            db,
            backupDir,
            gate,
            inspector(DatabaseSpecs.BACKEND)
        )

        val created = service.backup()

        assertNotNull(created)
        assertEquals("sb_backend_1000.db", created!!.name)
        assertEquals("ORIGINAL", created.readText())
        assertEquals(listOf(created), service.listBackups())

        assertTrue(service.restore(candidate))
        assertEquals("RESTORED", db.readText())
        assertFalse(
            File(db.parentFile, ".${db.name}.restore.tmp").exists()
        )
        assertFalse(
            File(db.parentFile, ".${db.name}.restore.rollback").exists()
        )
    }

    @Test
    fun signalsBackupNeverReadsOrWritesBackendDatabase() {
        val signalsDb = temporaryFolder.newFile("sb_signals.db")
        signalsDb.writeText("SIGNALS")
        val backendDb = temporaryFolder.newFile("sb_backend.db")
        backendDb.writeText("BACKEND")
        val backupDir = temporaryFolder.newFolder("signals_backups")
        val candidate = temporaryFolder.newFile("signals_candidate.db")
        candidate.writeText("NEW_SIGNALS")
        val gate = DatabaseMaintenanceGate()
        val service = backup(
            DatabaseSpecs.SIGNALS,
            signalsDb,
            backupDir,
            gate,
            inspector(DatabaseSpecs.SIGNALS)
        )

        assertTrue(service.restore(candidate))
        assertEquals("NEW_SIGNALS", signalsDb.readText())
        assertEquals("BACKEND", backendDb.readText())

        assertNull(
            backup(
                DatabaseSpecs.SIGNALS,
                backendDb,
                backupDir,
                gate,
                inspector(DatabaseSpecs.SIGNALS)
            ).backup()
        )
    }

    @Test
    fun busyCheckpointBlocksBackupWithoutCreatingFile() {
        val db = temporaryFolder.newFile("sb_backend.db")
        db.writeText("ORIGINAL")
        val backupDir = temporaryFolder.newFolder("busy_backups")
        val service = backup(
            DatabaseSpecs.BACKEND,
            db,
            backupDir,
            DatabaseMaintenanceGate(),
            inspector(DatabaseSpecs.BACKEND),
            checkpoint = { CheckpointResult(1, 2, 1) }
        )

        assertNull(service.backup())
        assertTrue(service.listBackups().isEmpty())
    }

    @Test
    fun heldMaintenanceGateBlocksRestore() {
        val db = temporaryFolder.newFile("sb_backend.db")
        db.writeText("ORIGINAL")
        val candidate = temporaryFolder.newFile("candidate.db")
        candidate.writeText("RESTORED")
        val gate = DatabaseMaintenanceGate()
        val held = gate.acquireMaintenance(1, TimeUnit.SECONDS)
        assertNotNull(held)
        val service = backup(
            DatabaseSpecs.BACKEND,
            db,
            temporaryFolder.newFolder("gated_backups"),
            gate,
            inspector(DatabaseSpecs.BACKEND)
        )

        assertFalse(service.restore(candidate))
        assertEquals("ORIGINAL", db.readText())
        held?.close()
    }

    @Test
    fun invalidCandidateNeverTouchesLiveDatabase() {
        val db = temporaryFolder.newFile("sb_backend.db")
        db.writeText("ORIGINAL")
        val bad = temporaryFolder.newFile("bad.db")
        bad.writeText("CORRUPT")
        val service = backup(
            DatabaseSpecs.BACKEND,
            db,
            temporaryFolder.newFolder("invalid_backups"),
            DatabaseMaintenanceGate(),
            inspector(DatabaseSpecs.BACKEND, corruptFileName = "bad.db")
        )

        assertFalse(service.restore(bad))
        assertEquals("ORIGINAL", db.readText())
    }

    @Test
    fun postReplaceValidationFailureRollsBackOriginal() {
        val db = temporaryFolder.newFile("sb_backend.db")
        db.writeText("ORIGINAL")
        val candidate = temporaryFolder.newFile("candidate.db")
        candidate.writeText("RESTORED")
        var failFinalValidation = false
        val service = backup(
            DatabaseSpecs.BACKEND,
            db,
            temporaryFolder.newFolder("rollback_backups"),
            DatabaseMaintenanceGate(),
            object : DatabaseFileInspector {
                override fun integrityCheck(file: File): String? =
                    if (failFinalValidation && file.name == "sb_backend.db") {
                        "corrupt"
                    } else {
                        "ok"
                    }

                override fun userVersion(file: File): Int =
                    SBDatabaseVersion.BACKEND

                override fun tableNames(file: File): Set<String> =
                    DatabaseSpecs.BACKEND.requiredTables
            },
            onBeforeReplace = { failFinalValidation = true }
        )

        assertFalse(service.restore(candidate))
        assertEquals("ORIGINAL", db.readText())
        assertFalse(
            File(db.parentFile, ".${db.name}.restore.rollback").exists()
        )
    }
}
