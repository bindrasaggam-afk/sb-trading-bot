package com.sb.signals.data

import android.content.Context
import com.sb.backend.database.AndroidDatabaseFileInspector
import com.sb.backend.database.CheckpointResult
import com.sb.backend.database.DatabaseFileBackup
import com.sb.backend.database.DatabaseMaintenanceGate
import com.sb.backend.database.DatabaseSpecs
import java.io.File

class SignalDatabaseBackup(
    context: Context,
    database: SignalDatabase? = null,
    maintenanceGate: DatabaseMaintenanceGate = DatabaseMaintenanceGate.shared
) {

    private val appContext = context.applicationContext
    private val spec = DatabaseSpecs.SIGNALS
    private val delegate = DatabaseFileBackup(
        databaseFile = appContext.getDatabasePath(spec.name),
        backupDirectory = File(appContext.filesDir, spec.backupSubdir),
        spec = spec,
        gate = maintenanceGate,
        inspector = AndroidDatabaseFileInspector(),
        onBeforeReplace = { database?.close() },
        checkpoint = { checkpoint(database) }
    )

    fun backup(prefix: String = spec.backupPrefix): File? = delegate.backup(prefix)

    fun restore(file: File): Boolean = delegate.restore(file)

    fun listBackups(): List<File> = delegate.listBackups()

    fun latestBackup(): File? = delegate.latestBackup()

    fun backupDirectory(): File = delegate.backupDirectory()

    private fun checkpoint(helper: SignalDatabase?): CheckpointResult? {
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
