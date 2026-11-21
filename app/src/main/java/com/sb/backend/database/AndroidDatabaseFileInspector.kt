package com.sb.backend.database

import android.database.sqlite.SQLiteDatabase
import java.io.File

class AndroidDatabaseFileInspector : DatabaseFileInspector {

    override fun integrityCheck(file: File): String? {
        return open(file).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }
    }

    override fun userVersion(file: File): Int? {
        return open(file).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else null
            }
        }
    }

    override fun tableNames(file: File): Set<String> {
        return open(file).use { db ->
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'",
                null
            ).use { cursor ->
                val names = linkedSetOf<String>()
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { names.add(it) }
                }
                names
            }
        }
    }

    private fun open(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            file.path,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
}
