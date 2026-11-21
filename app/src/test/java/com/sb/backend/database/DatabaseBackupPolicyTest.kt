package com.sb.backend.database

import com.sb.backend.database.DatabaseBackupPolicy.checkpointSucceeded
import com.sb.backend.database.DatabaseBackupPolicy.hasRequiredTables
import com.sb.backend.database.DatabaseBackupPolicy.isBackupInScope
import com.sb.backend.database.DatabaseBackupPolicy.isIntegrityOk
import com.sb.backend.database.DatabaseBackupPolicy.isOwnerFile
import com.sb.backend.database.DatabaseBackupPolicy.isVersionCompatible
import com.sb.backend.database.DatabaseBackupPolicy.nextBackupFileName
import com.sb.signals.data.SignalMigrations
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatabaseBackupPolicyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class FakeInspector(
        private val integrity: String?,
        private val version: Int?,
        private val tables: Set<String>
    ) : DatabaseFileInspector {
        override fun integrityCheck(file: File): String? = integrity
        override fun userVersion(file: File): Int? = version
        override fun tableNames(file: File): Set<String> = tables
    }

    @Test
    fun specsStayInSyncWithOwnerConstantsAndRemainSeparate() {
        assertEquals(SBDatabaseHelper.DATABASE_VERSION, SBDatabaseVersion.BACKEND)
        assertEquals(SignalMigrations.VERSION, SBDatabaseVersion.SIGNALS)
        assertEquals("sb_backend.db", DatabaseSpecs.BACKEND.name)
        assertEquals("sb_signals.db", DatabaseSpecs.SIGNALS.name)
        assertFalse(DatabaseSpecs.BACKEND.name == DatabaseSpecs.SIGNALS.name)
        assertFalse(
            DatabaseSpecs.BACKEND.backupSubdir ==
                DatabaseSpecs.SIGNALS.backupSubdir
        )
        assertTrue(
            isOwnerFile("sb_backend.db", DatabaseSpecs.BACKEND)
        )
        assertFalse(
            isOwnerFile("sb_backend.db", DatabaseSpecs.SIGNALS)
        )
        assertFalse(
            isBackupInScope("sb_signals_1.db", DatabaseSpecs.BACKEND)
        )
        assertTrue(
            isBackupInScope("sb_signals_1.db", DatabaseSpecs.SIGNALS)
        )
    }

    @Test
    fun backupNamesAvoidCollisionAndPreserveOwnerPrefix() {
        assertEquals(
            "sb_backend_1000.db",
            nextBackupFileName("sb_backend", 1000L, emptyList())
        )
        assertEquals(
            "sb_backend_1000_1.db",
            nextBackupFileName(
                "sb_backend",
                1000L,
                listOf("sb_backend_1000.db")
            )
        )
        assertEquals(
            "sb_signals_1000_2.db",
            nextBackupFileName(
                "sb_signals",
                1000L,
                listOf(
                    "sb_signals_1000.db",
                    "sb_signals_1000_1.db"
                )
            )
        )
    }

    @Test
    fun checkpointIntegrityVersionAndTablesAreChecked() {
        assertTrue(checkpointSucceeded(CheckpointResult(0, 3, 3)))
        assertFalse(checkpointSucceeded(CheckpointResult(1, 3, 2)))
        assertFalse(checkpointSucceeded(null))

        assertTrue(isIntegrityOk("OK"))
        assertFalse(isIntegrityOk("database disk image is malformed"))
        assertFalse(isIntegrityOk(null))

        assertTrue(isVersionCompatible(11, 11))
        assertFalse(isVersionCompatible(10, 11))
        assertFalse(isVersionCompatible(null, 11))

        assertTrue(
            hasRequiredTables(
                setOf("instruments", "candles", "quotes", "signals", "paper_account", "extra"),
                DatabaseSpecs.BACKEND.requiredTables
            )
        )
        assertFalse(
            hasRequiredTables(
                setOf("instruments", "candles"),
                DatabaseSpecs.BACKEND.requiredTables
            )
        )
    }

    @Test
    fun validatorAcceptsOnlyMatchingOwnerIntegrityVersionAndSchema() {
        val valid = temporaryFolder.newFile("candidate.db")
        valid.writeText("sqlite-image")

        val ok = DatabaseBackupValidator(
            DatabaseSpecs.BACKEND,
            FakeInspector(
                "ok",
                SBDatabaseVersion.BACKEND,
                DatabaseSpecs.BACKEND.requiredTables
            )
        ).validate(valid)
        assertTrue(ok.ok)
        assertEquals("", ok.reason)

        val badIntegrity = DatabaseBackupValidator(
            DatabaseSpecs.BACKEND,
            FakeInspector(
                "malformed",
                SBDatabaseVersion.BACKEND,
                DatabaseSpecs.BACKEND.requiredTables
            )
        ).validate(valid)
        assertFalse(badIntegrity.ok)
        assertEquals("integrity-check-failed", badIntegrity.reason)

        val badVersion = DatabaseBackupValidator(
            DatabaseSpecs.BACKEND,
            FakeInspector(
                "ok",
                5,
                DatabaseSpecs.BACKEND.requiredTables
            )
        ).validate(valid)
        assertFalse(badVersion.ok)
        assertEquals("version-mismatch", badVersion.reason)

        val badSchema = DatabaseBackupValidator(
            DatabaseSpecs.SIGNALS,
            FakeInspector(
                "ok",
                SBDatabaseVersion.SIGNALS,
                setOf("research_signals")
            )
        ).validate(valid)
        assertFalse(badSchema.ok)
        assertEquals("missing-required-tables", badSchema.reason)

        val empty = temporaryFolder.newFile("empty.db")
        val emptyResult = DatabaseBackupValidator(
            DatabaseSpecs.BACKEND,
            FakeInspector(
                "ok",
                SBDatabaseVersion.BACKEND,
                DatabaseSpecs.BACKEND.requiredTables
            )
        ).validate(empty)
        assertFalse(emptyResult.ok)
        assertEquals("missing-or-empty", emptyResult.reason)
    }
}
