package com.sb.signals.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignalMigrationsInstrumentedTest {

    @Test
    fun upgradeFromVersionThreeAddsScoresAndAnalyticsTables() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL(
                """
                CREATE TABLE research_signals (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    symbol TEXT NOT NULL,
                    exchange TEXT NOT NULL,
                    category TEXT NOT NULL,
                    status TEXT NOT NULL,
                    generated_at INTEGER NOT NULL,
                    indicator_params_json TEXT NOT NULL DEFAULT '{}'
                )
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO research_signals(" +
                    "symbol, exchange, category, status, generated_at" +
                    ") VALUES('SB_TEST', 'NSE', 'INTRADAY', 'NEW', 2)"
            )

            SignalMigrations.upgrade(db, oldVersion = 3)

            assertTrue(
                hasColumn(
                    db,
                    SignalMigrations.TABLE_SIGNALS,
                    "indicator_scores_json"
                )
            )
            assertTrue(tableExists(db, "research_indicator_snapshots"))
            assertTrue(tableExists(db, "research_market_snapshots"))
            assertTrue(tableExists(db, "analytics_export_history"))
            db.rawQuery(
                "SELECT COUNT(*) FROM research_signals " +
                    "WHERE symbol = 'SB_TEST'",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun upgradeIsIdempotentAfterVersionFour() {
        val db = SQLiteDatabase.create(null)
        try {
            SignalMigrations.createAll(db)
            SignalMigrations.upgrade(db, oldVersion = 3)
            SignalMigrations.upgrade(db, oldVersion = 3)
            assertTrue(
                tableExists(db, "research_indicator_snapshots")
            )
            assertTrue(tableExists(db, "analytics_export_history"))
        } finally {
            db.close()
        }
    }

    private fun hasColumn(
        db: SQLiteDatabase,
        table: String,
        column: String
    ): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == column) return true
            }
        }
        return false
    }

    private fun tableExists(
        db: SQLiteDatabase,
        table: String
    ): Boolean {
        db.rawQuery(
            "SELECT name FROM sqlite_master " +
                "WHERE type='table' AND name=?",
            arrayOf(table)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }
}
