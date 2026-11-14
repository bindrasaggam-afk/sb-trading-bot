package com.sb.backend.database

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sb.backend.market.analysis.CandleSeriesAudit
import com.sb.backend.market.model.MarketCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationsInstrumentedTest {

    @Test
    fun upgradePreservesLegacyQuoteAndBackfillsReceiveTime() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL(
                """
                CREATE TABLE quotes (
                    symbol TEXT NOT NULL,
                    exchange TEXT NOT NULL,
                    ltp REAL NOT NULL,
                    timestamp INTEGER NOT NULL,
                    PRIMARY KEY(symbol, exchange)
                )
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO quotes(symbol, exchange, ltp, timestamp) VALUES(?, ?, ?, ?)",
                arrayOf("SBTEST", "NSE", 123.45, 1700000000000L)
            )

            DatabaseMigrations.upgrade(db, oldVersion = 1)

            db.rawQuery(
                "SELECT ltp, received_at FROM quotes WHERE symbol = ? AND exchange = ?",
                arrayOf("SBTEST", "NSE")
            ).use { cursor ->
                assertTrue("Legacy quote must remain after migration", cursor.moveToFirst())
                assertEquals(123.45, cursor.getDouble(0), 0.0001)
                assertEquals(1700000000000L, cursor.getLong(1))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun schemaCreationIsIdempotentAndKeepsExistingRows() {
        val db = SQLiteDatabase.create(null)
        try {
            DatabaseMigrations.createAll(db)
            db.execSQL(
                """
                INSERT INTO instruments(
                    symbol, name, exchange, segment, instrument_type,
                    trading_symbol, lot_size, tick_size, is_active
                ) VALUES('SBTEST', 'SB Test', 'NSE', 'EQ', 'EQ',
                         'SBTEST', 1, 0.05, 1)
                """.trimIndent()
            )

            DatabaseMigrations.createAll(db)
            DatabaseMigrations.upgrade(db, oldVersion = 4)

            db.rawQuery(
                "SELECT COUNT(*) FROM instruments WHERE symbol = ?",
                arrayOf("SBTEST")
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun candleValidationRejectsInvalidOhlcWithoutDeletingExistingRows() {
        val db = SQLiteDatabase.create(null)
        try {
            DatabaseMigrations.createAll(db)
            db.execSQL(
                """
                INSERT INTO candles(
                    symbol, exchange, timeframe, timestamp,
                    open, high, low, close, volume
                ) VALUES('SBTEST', 'NSE', '1m', 1700000000000,
                         100, 110, 90, 105, 10)
                """.trimIndent()
            )

            var rejected = false
            try {
                db.execSQL(
                    """
                    INSERT INTO candles(
                        symbol, exchange, timeframe, timestamp,
                        open, high, low, close, volume
                    ) VALUES('BADTEST', 'NSE', '1m', 1700000000060,
                             100, 95, 90, 105, 10)
                    """.trimIndent()
                )
            } catch (_: SQLiteException) {
                rejected = true
            }

            assertTrue("Invalid OHLC candle must be rejected", rejected)
            db.rawQuery(
                "SELECT COUNT(*) FROM candles WHERE symbol = 'SBTEST'",
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
    fun sqliteStoredCandlesCanBeAuditedAgainstIndependentLiveSnapshot() {
        val db = SQLiteDatabase.create(null)
        try {
            DatabaseMigrations.createAll(db)
            val storedRows = listOf(
                MarketCandle(1_700_000_000_000L, 100.0, 102.0, 99.0, 101.0, 120L),
                MarketCandle(1_700_000_060_000L, 101.0, 103.0, 100.0, 102.0, 150L)
            )
            db.beginTransaction()
            try {
                storedRows.forEach { candle ->
                    db.execSQL(
                        """
                        INSERT INTO candles(
                            symbol, exchange, timeframe, timestamp,
                            open, high, low, close, volume
                        ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf(
                            "SB_AUDIT", "NSE", "1m", candle.timestamp,
                            candle.open, candle.high, candle.low, candle.close, candle.volume
                        )
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            val readBack = ArrayList<MarketCandle>()
            db.rawQuery(
                """
                SELECT timestamp, open, high, low, close, volume
                FROM candles
                WHERE symbol = ? AND exchange = ? AND timeframe = ?
                ORDER BY timestamp ASC
                """.trimIndent(),
                arrayOf("SB_AUDIT", "NSE", "1m")
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    readBack.add(
                        MarketCandle(
                            timestamp = cursor.getLong(0),
                            open = cursor.getDouble(1),
                            high = cursor.getDouble(2),
                            low = cursor.getDouble(3),
                            close = cursor.getDouble(4),
                            volume = cursor.getLong(5)
                        )
                    )
                }
            }

            val independentLiveSnapshot = storedRows.map { it.copy() }
            val audit = CandleSeriesAudit.compare(readBack, independentLiveSnapshot)

            assertEquals(storedRows.size, readBack.size)
            assertTrue("Stored and live candle snapshots should match", audit.exactMatch)
            assertEquals(storedRows.size, audit.matchedTimestamps)
        } finally {
            db.close()
        }
    }

}
