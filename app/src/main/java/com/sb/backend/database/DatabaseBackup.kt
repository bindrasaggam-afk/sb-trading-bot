package com.sb.backend.database

import android.content.Context
import java.io.File

class DatabaseBackup(
    context: Context,
    databaseHelper: SBDatabaseHelper? = null,
    maintenanceGate: DatabaseMaintenanceGate = DatabaseMaintenanceGate.shared
) {

    private val appContext = context.applicationContext
    private val spec = DatabaseSpecs.BACKEND
    private val delegate = DatabaseFileBackup(
        databaseFile = appContext.getDatabasePath(spec.name),
        backupDirectory = File(appContext.filesDir, spec.backupSubdir),
        spec = spec,
        gate = maintenanceGate,
        inspector = AndroidDatabaseFileInspector(),
        onBeforeReplace = { databaseHelper?.close() },
        checkpoint = { checkpoint(databaseHelper) }
    )

    fun backup(prefix: String = spec.backupPrefix): File? = delegate.backup(prefix)

    fun restore(file: File): Boolean = delegate.restore(file)

    fun listBackups(): List<File> = delegate.listBackups()

    fun latestBackup(): File? = delegate.latestBackup()

    fun deleteOlderThan(days: Int): Int = delegate.deleteOlderThan(days)

    fun backupDirectory(): File = delegate.backupDirectory()

    private fun checkpoint(helper: SBDatabaseHelper?): CheckpointResult? {
        helper ?: return null
        return helper.writableDatabase
            .rawQuery("PRAGMA wal_checkpoint(FULL)", null)
            .use { cursor ->
                if (cursor.moveToFirst()) {
                    CheckpointResult(
                        busy = cursor.getInt(0),
                        log = cursor.getInt(1),
                        checkpointed = cursor.getInt(2)
                    )
                } else {
                    null
                }
            }
    }
}
