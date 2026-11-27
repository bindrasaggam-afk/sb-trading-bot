package com.sb.backend.database

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

class DatabaseFileBackup(
    private val databaseFile: File,
    private val backupDirectory: File,
    private val spec: DatabaseSpec,
    private val gate: DatabaseMaintenanceGate,
    private val inspector: DatabaseFileInspector,
    private val onBeforeReplace: () -> Unit = {},
    private val checkpoint: () -> CheckpointResult? = { null },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val maintenanceTimeoutSeconds: Long =
        DatabaseMaintenanceGate.DEFAULT_TIMEOUT_SECONDS
) {

    private val validator = DatabaseBackupValidator(spec, inspector)
    private val sidecarSuffixes = listOf("-wal", "-shm", "-journal")

    fun backup(prefix: String = spec.backupPrefix): File? {
        return try {
            gate.withMaintenance(maintenanceTimeoutSeconds, TimeUnit.SECONDS) {
                if (!DatabaseBackupPolicy.isOwnerFile(
                        databaseFile.name,
                        spec
                    )
                ) {
                    return@withMaintenance null
                }
                val checkpointResult = checkpoint()
                if (checkpointResult != null &&
                    !DatabaseBackupPolicy.checkpointSucceeded(checkpointResult)
                ) {
                    return@withMaintenance null
                }
                if (!databaseFile.exists() ||
                    !DatabaseBackupPolicy.isNonEmpty(databaseFile.length())
                ) {
                    return@withMaintenance null
                }
                if (!backupDirectory.exists() && !backupDirectory.mkdirs()) {
                    return@withMaintenance null
                }

                val existing = backupDirectory.listFiles()
                    ?.map { it.name }
                    .orEmpty()
                val target = File(
                    backupDirectory,
                    DatabaseBackupPolicy.nextBackupFileName(
                        prefix,
                        nowMillis(),
                        existing
                    )
                )
                copyFile(databaseFile, target)
                if (validator.validate(target).ok) {
                    target
                } else {
                    target.delete()
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun restore(file: File): Boolean {
        if (!file.exists() ||
            !DatabaseBackupPolicy.isNonEmpty(file.length())
        ) {
            return false
        }

        return try {
            gate.withMaintenance(maintenanceTimeoutSeconds, TimeUnit.SECONDS) {
                if (!DatabaseBackupPolicy.isOwnerFile(
                        databaseFile.name,
                        spec
                    )
                ) {
                    return@withMaintenance false
                }
                if (!validator.validate(file).ok) {
                    return@withMaintenance false
                }

                val parent = databaseFile.parentFile ?: return@withMaintenance false
                val temp = File(parent, ".${databaseFile.name}.restore.tmp")
                val rollback = File(
                    parent,
                    ".${databaseFile.name}.restore.rollback"
                )
                var touched = false
                var success = false

                try {
                    copyFile(file, temp)
                    if (!validator.validate(temp).ok) {
                        return@withMaintenance false
                    }
                    if (databaseFile.exists()) {
                        copyFile(databaseFile, rollback)
                    }

                    onBeforeReplace()
                    deleteSidecars(databaseFile)
                    touched = true
                    if (!replaceFile(temp, databaseFile)) {
                        return@withMaintenance false
                    }
                    deleteSidecars(databaseFile)
                    if (!validator.validate(databaseFile).ok) {
                        return@withMaintenance false
                    }
                    success = true
                    true
                } finally {
                    temp.delete()
                    if (!success) {
                        val recovered = if (touched) {
                            rollbackTo(rollback, databaseFile)
                        } else {
                            false
                        }
                        if (!touched || recovered) rollback.delete()
                    } else {
                        rollback.delete()
                    }
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    fun listBackups(): List<File> {
        val files = backupDirectory.listFiles() ?: return emptyList()
        return files.filter { file ->
            file.isFile &&
                DatabaseBackupPolicy.isBackupInScope(file.name, spec)
        }.sortedByDescending { it.lastModified() }
    }

    fun latestBackup(): File? = listBackups().firstOrNull()

    fun deleteOlderThan(days: Int): Int {
        require(days >= 0)
        val cutoff = System.currentTimeMillis() -
            days * 24L * 60L * 60L * 1_000L
        var deleted = 0
        listBackups().forEach { file ->
            if (file.lastModified() < cutoff && file.delete()) deleted++
        }
        return deleted
    }

    fun backupDirectory(): File = backupDirectory

    private fun rollbackTo(rollback: File, target: File): Boolean {
        if (!rollback.exists()) return false
        deleteSidecars(target)
        val restored = replaceFile(rollback, target)
        if (restored) deleteSidecars(target)
        return restored
    }

    private fun replaceFile(source: File, target: File): Boolean {
        return try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
            true
        } catch (_: Exception) {
            try {
                copyFile(source, target)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun copyFile(source: File, target: File) {
        target.parentFile?.mkdirs()
        source.inputStream().use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun deleteSidecars(databaseFile: File) {
        sidecarSuffixes.forEach { suffix ->
            File(databaseFile.path + suffix).delete()
        }
    }

    internal fun waitForDrain(timeoutSeconds: Long): Boolean {
        val lease = gate.acquireMaintenance(
            timeoutSeconds,
            TimeUnit.SECONDS
        ) ?: return false
        lease.close()
        return true
    }
}
