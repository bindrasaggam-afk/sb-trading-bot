package com.sb.backend.database

import java.io.File

data class DatabaseSpec(
    val name: String,
    val owner: String,
    val expectedUserVersion: Int,
    val requiredTables: Set<String>,
    val backupPrefix: String,
    val backupSubdir: String
)

object DatabaseSpecs {
    val BACKEND = DatabaseSpec(
        name = "sb_backend.db",
        owner = "com.sb.backend.core.BackendManager",
        expectedUserVersion = SBDatabaseVersion.BACKEND,
        requiredTables = setOf(
            "instruments",
            "quotes",
            "candles",
            "signals",
            "paper_account"
        ),
        backupPrefix = "sb_backend",
        backupSubdir = "db_backups"
    )

    val SIGNALS = DatabaseSpec(
        name = "sb_signals.db",
        owner = "com.sb.signals.services.SignalsPlatform",
        expectedUserVersion = SBDatabaseVersion.SIGNALS,
        requiredTables = setOf(
            "research_signals",
            "strategy_versions",
            "analysis_runs"
        ),
        backupPrefix = "sb_signals",
        backupSubdir = "db_backups/signals"
    )
}

object SBDatabaseVersion {
    const val BACKEND = 11
    const val SIGNALS = 4
}

data class CheckpointResult(
    val busy: Int,
    val log: Int,
    val checkpointed: Int
)

data class ValidationResult(
    val ok: Boolean,
    val reason: String = ""
)

interface DatabaseFileInspector {
    fun integrityCheck(file: File): String?
    fun userVersion(file: File): Int?
    fun tableNames(file: File): Set<String>
}

object DatabaseBackupPolicy {
    fun backupFileName(prefix: String, nowMillis: Long): String =
        "${prefix}_${nowMillis}.db"

    fun nextBackupFileName(
        prefix: String,
        nowMillis: Long,
        existingNames: Collection<String>
    ): String {
        val base = backupFileName(prefix, nowMillis)
        if (base !in existingNames) return base
        var suffix = 1
        while ("${prefix}_${nowMillis}_${suffix}.db" in existingNames) {
            suffix++
        }
        return "${prefix}_${nowMillis}_${suffix}.db"
    }

    fun isNonEmpty(size: Long): Boolean = size > 0L

    fun checkpointSucceeded(result: CheckpointResult?): Boolean =
        result != null && result.busy == 0

    fun isIntegrityOk(result: String?): Boolean =
        result?.equals("ok", ignoreCase = true) == true

    fun isVersionCompatible(actual: Int?, expected: Int): Boolean =
        actual == expected

    fun hasRequiredTables(
        actual: Set<String>,
        required: Set<String>
    ): Boolean = actual.containsAll(required)

    fun isBackupInScope(name: String, spec: DatabaseSpec): Boolean =
        name.startsWith("${spec.backupPrefix}_") && name.endsWith(".db")

    fun isOwnerFile(name: String, spec: DatabaseSpec): Boolean =
        name == spec.name
}

class DatabaseBackupValidator(
    private val spec: DatabaseSpec,
    private val inspector: DatabaseFileInspector
) {
    fun validate(file: File): ValidationResult {
        if (!file.exists() || !DatabaseBackupPolicy.isNonEmpty(file.length())) {
            return ValidationResult(false, "missing-or-empty")
        }
        val integrity = try {
            inspector.integrityCheck(file)
        } catch (_: Exception) {
            null
        }
        if (!DatabaseBackupPolicy.isIntegrityOk(integrity)) {
            return ValidationResult(false, "integrity-check-failed")
        }
        val version = try {
            inspector.userVersion(file)
        } catch (_: Exception) {
            null
        }
        if (!DatabaseBackupPolicy.isVersionCompatible(
                version,
                spec.expectedUserVersion
            )
        ) {
            return ValidationResult(false, "version-mismatch")
        }
        val tables = try {
            inspector.tableNames(file)
        } catch (_: Exception) {
            emptySet()
        }
        if (!DatabaseBackupPolicy.hasRequiredTables(tables, spec.requiredTables)) {
            return ValidationResult(false, "missing-required-tables")
        }
        return ValidationResult(true)
    }
}
