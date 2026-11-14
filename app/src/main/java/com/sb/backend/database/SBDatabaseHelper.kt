package com.sb.backend.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SBDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)

        // Enable foreign key constraints.
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        DatabaseMigrations.createAll(db)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (newVersion <= oldVersion) return

        DatabaseMigrations.upgrade(db, oldVersion)
    }

    override fun onDowngrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // Never drop or recreate existing tables.
        // Preserve existing user data during downgrade.
    }

    companion object {

        const val DATABASE_NAME = "sb_backend.db"

        // Must match the latest DatabaseMigrations version.
        const val DATABASE_VERSION = 6

        // Core market tables
        const val TABLE_INSTRUMENTS = "instruments"
        const val TABLE_QUOTES = "quotes"
        const val TABLE_CANDLES = "candles"

        // Tick persistence and audit tables
        const val TABLE_RAW_TICKS = "raw_ticks"
        const val TABLE_TICK_AUDIT = "tick_audit"
        const val TABLE_QUOTE_PERSISTENCE_RETRY =
            "quote_persistence_retry"

        // Trading tables
        const val TABLE_SIGNALS = "signals"
        const val TABLE_PAPER_ACCOUNT = "paper_account"
        const val TABLE_PAPER_ORDERS = "paper_orders"
        const val TABLE_POSITIONS = "positions"

        // Application tables
        const val TABLE_DEVICE_IDENTITY = "device_identity"
        const val TABLE_APP_STATE = "app_state"
    }
}