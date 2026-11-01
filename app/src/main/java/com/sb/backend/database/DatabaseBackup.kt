package com.sb.backend.database

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class DatabaseBackup(
    context: Context,
    private val databaseHelper: SBDatabaseHelper? = null
) {

    private val appContext = context.applicationContext

    fun backup(prefix: String = "sb_backend"): File? {
        return try {
            checkpoint()
            val source = appContext.getDatabasePath(
                SBDatabaseHelper.DATABASE_NAME
            )
            if (!source.exists() || source.length() == 0L) return null

            val target = File(
                backupDirectory(),
                "${prefix}_${System.currentTimeMillis()}.db"
            )
            copyFile(source, target)
            target
        } catch (e: Exception) {
            null
        }
    }

    fun restore(file: File): Boolean {
        return try {
            if (!file.exists() || file.length() == 0L) return false
            val target = appContext.getDatabasePath(
                SBDatabaseHelper.DATABASE_NAME
            )
            target.parentFile?.mkdirs()
            copyFile(file, target)
            deleteSidecarFiles(target)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun listBackups(): List<File> {
        val directory = backupDirectory()
        val files = directory.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(".db") }
            .sortedByDescending { it.lastModified() }
    }

    fun latestBackup(): File? = listBackups().firstOrNull()

    fun deleteOlderThan(days: Int): Int {
        require(days >= 0) { "Days must be non-negative." }
        val cutoff = System.currentTimeMillis() -
            days.toLong() * 24L * 60L * 60L * 1000L
        var deleted = 0
        listBackups().forEach { file ->
            if (file.lastModified() < cutoff && file.delete()) {
                deleted++
            }
        }
        return deleted
    }

    fun backupDirectory(): File {
        val directory = File(appContext.filesDir, "db_backups")
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return directory
    }

    private fun checkpoint() {
        val helper = databaseHelper ?: return
        helper.writableDatabase
            .rawQuery("PRAGMA wal_checkpoint(FULL)", null)
            .use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getInt(0)
                }
            }
    }

    private fun copyFile(source: File, target: File) {
        target.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
    }

    private fun deleteSidecarFiles(databaseFile: File) {
        File(databaseFile.path + "-wal").delete()
        File(databaseFile.path + "-shm").delete()
        File(databaseFile.path + "-journal").delete()
    }
}
