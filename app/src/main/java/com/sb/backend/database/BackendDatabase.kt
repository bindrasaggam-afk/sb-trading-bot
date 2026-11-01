package com.sb.backend.database

import android.content.Context

class BackendDatabase(context: Context) {

    private val appContext = context.applicationContext

    // केवल sb_backend.db इस्तेमाल होगा
    val databaseHelper = SBDatabaseHelper(appContext)

    val readableDatabase
        get() = databaseHelper.readableDatabase

    val writableDatabase
        get() = databaseHelper.writableDatabase

    fun isDatabaseOpen(): Boolean {
        return try {
            writableDatabase.isOpen
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        databaseHelper.close()
    }
}
