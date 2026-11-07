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
        DatabaseMigrations.createAll(db)
    }

    companion object {
        const val DATABASE_NAME = "sb_backend.db"
        const val DATABASE_VERSION = 3

        const val TABLE_INSTRUMENTS = "instruments"
        const val TABLE_QUOTES = "quotes"
        const val TABLE_CANDLES = "candles"
        const val TABLE_SIGNALS = "signals"
        const val TABLE_PAPER_ACCOUNT = "paper_account"
        const val TABLE_PAPER_ORDERS = "paper_orders"
        const val TABLE_POSITIONS = "positions"
        const val TABLE_DEVICE_IDENTITY = "device_identity"
        const val TABLE_APP_STATE = "app_state"
    }
}
