package com.sb.backend.database

import android.content.Context

class BackendDatabaseTest(private val context: Context) {

    fun testConnection(): String {
        return try {
            val database = BackendDatabase(context)
            val db = database.writableDatabase

            val cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'",
                null
            )

            val tables = mutableListOf<String>()

            while (cursor.moveToNext()) {
                tables.add(cursor.getString(0))
            }

            cursor.close()
            database.close()

            "PASS: sb_backend.db opened. Tables: ${tables.joinToString(", ")}"

        } catch (e: Exception) {
            "FAIL: ${e.message}"
        }
    }
}
