package com.sb.backend.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyPaperMigrationInstrumentedTest {

    @Test
    fun migratesLegacyLedgerWithoutChangingCashOrPnl() {
        val legacy = createLegacyDatabase()
        val main = createMainDatabase()
        try {
            seedLegacyAccount(legacy, balance = 10_150.0)
            seedLegacyOrder(
                legacy,
                id = 1,
                symbol = "SBOPENBUY",
                side = "BUY",
                quantity = 10,
                entryPrice = 100.0,
                exitPrice = null,
                realizedPnl = null,
                status = "OPEN",
                reservedMargin = 0.0,
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_000_000L
            )
            seedLegacyOrder(
                legacy,
                id = 2,
                symbol = "SBCLOSEDSELL",
                side = "SELL",
                quantity = 5,
                entryPrice = 200.0,
                exitPrice = 190.0,
                realizedPnl = 50.0,
                status = "CLOSED",
                reservedMargin = 0.0,
                createdAt = 1_700_000_010_000L,
                updatedAt = 1_700_000_020_000L
            )
            seedLegacyOrder(
                legacy,
                id = 3,
                symbol = "SBOPENSHORT",
                side = "SELL",
                quantity = 2,
                entryPrice = 300.0,
                exitPrice = null,
                realizedPnl = null,
                status = "OPEN",
                reservedMargin = 120.0,
                createdAt = 1_700_000_030_000L,
                updatedAt = 1_700_000_030_000L
            )
            seedLegacyOrder(
                legacy,
                id = 4,
                symbol = "SBCLOSEDBUY",
                side = "BUY",
                quantity = 10,
                entryPrice = 100.0,
                exitPrice = 110.0,
                realizedPnl = 100.0,
                status = "CLOSED",
                reservedMargin = 0.0,
                createdAt = 1_700_000_040_000L,
                updatedAt = 1_700_000_050_000L
            )

            val result = LegacyPaperMigration.migrate(
                legacyDb = legacy,
                mainDb = main,
                defaultExchange = "NSE",
                shortMarginRate = 0.20,
                now = 1_800_000_000_000L
            )

            assertTrue(result.applied)
            assertFalse(result.skipped)
            assertEquals(4, result.legacyOrders)
            assertEquals(4, result.insertedOrders)
            assertEquals(4, result.ensuredPositions)
            assertNotNull(LegacyPaperMigration.readMarker(main))

            assertEquals(11_000.0, accountDouble(main, "starting_balance"), 0.0001)
            assertEquals(10_150.0, accountDouble(main, "balance"), 0.0001)
            assertEquals(120.0, accountDouble(main, "reserved_margin"), 0.0001)
            assertEquals(150.0, accountDouble(main, "realized_pnl"), 0.0001)
            assertEquals(4, count(main, "paper_orders"))
            assertEquals(4, count(main, "positions"))
            assertEquals(2, count(main, "positions", "status = 'OPEN'"))
            assertEquals(2, count(main, "paper_orders", "status = 'CLOSED'"))
        } finally {
            legacy.close()
            main.close()
        }
    }

    @Test
    fun rerunIsIdempotentAfterSuccessfulMigration() {
        val legacy = createLegacyDatabase()
        val main = createMainDatabase()
        try {
            seedLegacyAccount(legacy, balance = 1_000.0)
            seedLegacyOrder(
                legacy,
                id = 10,
                symbol = "SBTEST",
                side = "BUY",
                quantity = 2,
                entryPrice = 100.0,
                exitPrice = null,
                realizedPnl = null,
                status = "OPEN",
                reservedMargin = 0.0,
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_000_000L
            )

            val first = LegacyPaperMigration.migrate(
                legacyDb = legacy,
                mainDb = main,
                now = 1_800_000_000_000L
            )
            val second = LegacyPaperMigration.migrate(
                legacyDb = legacy,
                mainDb = main,
                now = 1_800_000_001_000L
            )

            assertTrue(first.applied)
            assertFalse(second.applied)
            assertTrue(second.skipped)
            assertEquals(1, count(main, "paper_orders"))
            assertEquals(1, count(main, "positions"))
            assertEquals(1, count(main, "paper_orders", "legacy_order_id = 10"))
        } finally {
            legacy.close()
            main.close()
        }
    }

    @Test
    fun mergesLegacyLedgerIntoExistingMainAccount() {
        val legacy = createLegacyDatabase()
        val main = createMainDatabase()
        try {
            seedLegacyAccount(legacy, balance = 2_100.0)
            seedLegacyOrder(
                legacy,
                id = 20,
                symbol = "SBLEGACY",
                side = "BUY",
                quantity = 1,
                entryPrice = 100.0,
                exitPrice = 120.0,
                realizedPnl = 20.0,
                status = "CLOSED",
                reservedMargin = 0.0,
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_010_000L
            )

            seedMainAccount(
                main,
                startingBalance = 5_000.0,
                balance = 4_500.0,
                reservedMargin = 100.0,
                realizedPnl = 0.0
            )
            val mainOrderId = seedMainOrder(
                main,
                symbol = "SBMAIN",
                side = "SELL",
                quantity = 1,
                entryPrice = 500.0,
                reservedMargin = 100.0,
                status = "OPEN",
                legacyOrderId = null
            )
            seedMainPosition(
                main,
                orderId = mainOrderId,
                symbol = "SBMAIN",
                side = "SELL",
                quantity = 1,
                entryPrice = 500.0,
                lastPrice = 500.0,
                reservedMargin = 100.0,
                status = "OPEN"
            )

            val result = LegacyPaperMigration.migrate(
                legacyDb = legacy,
                mainDb = main,
                now = 1_800_000_000_000L
            )

            assertTrue(result.applied)
            assertEquals(2, count(main, "paper_orders"))
            assertEquals(2, count(main, "positions"))
            assertEquals(7_080.0, accountDouble(main, "starting_balance"), 0.0001)
            assertEquals(6_600.0, accountDouble(main, "balance"), 0.0001)
            assertEquals(100.0, accountDouble(main, "reserved_margin"), 0.0001)
            assertEquals(20.0, accountDouble(main, "realized_pnl"), 0.0001)
            assertEquals(1, count(main, "paper_orders", "legacy_order_id = 20"))
        } finally {
            legacy.close()
            main.close()
        }
    }

    @Test
    fun failedMigrationRollsBackOrdersAndMarker() {
        val legacy = createLegacyDatabase()
        val main = createMainDatabase()
        try {
            seedLegacyAccount(legacy, balance = 1_000.0)
            seedLegacyOrder(
                legacy,
                id = 30,
                symbol = "SBROLLBACK",
                side = "BUY",
                quantity = 1,
                entryPrice = 100.0,
                exitPrice = null,
                realizedPnl = null,
                status = "OPEN",
                reservedMargin = 0.0,
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_000_000L
            )

            seedMainAccount(
                main,
                startingBalance = 5_000.0,
                balance = 4_500.0,
                reservedMargin = 999.0,
                realizedPnl = 0.0
            )
            seedMainOrder(
                main,
                symbol = "SBMAIN",
                side = "BUY",
                quantity = 1,
                entryPrice = 100.0,
                reservedMargin = 0.0,
                status = "OPEN",
                legacyOrderId = null
            )

            var failed = false
            try {
                LegacyPaperMigration.migrate(
                    legacyDb = legacy,
                    mainDb = main,
                    now = 1_800_000_000_000L
                )
            } catch (_: IllegalArgumentException) {
                failed = true
            }

            assertTrue("Inconsistent main ledger must fail verification", failed)
            assertNull(LegacyPaperMigration.readMarker(main))
            assertEquals(1, count(main, "paper_orders"))
            assertEquals(0, count(main, "paper_orders", "legacy_order_id IS NOT NULL"))
            assertEquals(0, count(main, "positions"))
        } finally {
            legacy.close()
            main.close()
        }
    }

    @Test
    fun skipsWhenLegacyDatabaseHasNoPaperData() {
        val legacy = createLegacyDatabase()
        val main = createMainDatabase()
        try {
            val result = LegacyPaperMigration.migrate(
                legacyDb = legacy,
                mainDb = main,
                now = 1_800_000_000_000L
            )

            assertFalse(result.applied)
            assertTrue(result.skipped)
            assertNull(LegacyPaperMigration.readMarker(main))
            assertEquals(0, count(main, "paper_orders"))
        } finally {
            legacy.close()
            main.close()
        }
    }

    private fun createLegacyDatabase(): SQLiteDatabase {
        return SQLiteDatabase.create(null).apply {
            execSQL(
                """
                CREATE TABLE paper_account (
                    id INTEGER PRIMARY KEY,
                    balance REAL NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            execSQL(
                """
                CREATE TABLE paper_orders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    symbol TEXT NOT NULL,
                    side TEXT NOT NULL,
                    quantity INTEGER NOT NULL,
                    entry_price REAL NOT NULL,
                    exit_price REAL,
                    realized_pnl REAL,
                    status TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    reserved_margin REAL
                )
                """.trimIndent()
            )
        }
    }

    private fun createMainDatabase(): SQLiteDatabase {
        return SQLiteDatabase.create(null).apply {
            DatabaseMigrations.createAll(this)
        }
    }

    private fun seedLegacyAccount(db: SQLiteDatabase, balance: Double) {
        db.execSQL(
            "INSERT INTO paper_account(id, balance, updated_at) VALUES(1, ?, ?)",
            arrayOf(balance, 1_700_000_000_000L)
        )
    }

    private fun seedLegacyOrder(
        db: SQLiteDatabase,
        id: Long,
        symbol: String,
        side: String,
        quantity: Int,
        entryPrice: Double,
        exitPrice: Double?,
        realizedPnl: Double?,
        status: String,
        reservedMargin: Double,
        createdAt: Long,
        updatedAt: Long
    ) {
        db.execSQL(
            """
            INSERT INTO paper_orders(
                id, symbol, side, quantity, entry_price, exit_price,
                realized_pnl, status, created_at, updated_at, reserved_margin
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                id, symbol, side, quantity, entryPrice, exitPrice,
                realizedPnl, status, createdAt, updatedAt, reservedMargin
            )
        )
    }

    private fun seedMainAccount(
        db: SQLiteDatabase,
        startingBalance: Double,
        balance: Double,
        reservedMargin: Double,
        realizedPnl: Double
    ) {
        db.execSQL(
            """
            INSERT INTO paper_account(
                id, starting_balance, balance, reserved_margin,
                realized_pnl, updated_at
            ) VALUES(1, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                startingBalance, balance, reservedMargin,
                realizedPnl, 1_700_000_000_000L
            )
        )
    }

    private fun seedMainOrder(
        db: SQLiteDatabase,
        symbol: String,
        side: String,
        quantity: Int,
        entryPrice: Double,
        reservedMargin: Double,
        status: String,
        legacyOrderId: Long?
    ): Long {
        db.execSQL(
            """
            INSERT INTO paper_orders(
                symbol, exchange, side, quantity, entry_price,
                reserved_margin, status, note, stop_loss, target,
                legacy_order_id, created_at, updated_at
            ) VALUES(?, 'NSE', ?, ?, ?, ?, ?, '', NULL, NULL, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                symbol, side, quantity, entryPrice, reservedMargin,
                status, legacyOrderId, 1_700_000_000_000L,
                1_700_000_000_000L
            )
        )
        return db.rawQuery("SELECT last_insert_rowid()", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
    }

    private fun seedMainPosition(
        db: SQLiteDatabase,
        orderId: Long,
        symbol: String,
        side: String,
        quantity: Int,
        entryPrice: Double,
        lastPrice: Double,
        reservedMargin: Double,
        status: String
    ) {
        db.execSQL(
            """
            INSERT INTO positions(
                order_id, symbol, exchange, side, quantity,
                entry_price, last_price, reserved_margin, status,
                opened_at, updated_at
            ) VALUES(?, ?, 'NSE', ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                orderId, symbol, side, quantity, entryPrice,
                lastPrice, reservedMargin, status,
                1_700_000_000_000L, 1_700_000_000_000L
            )
        )
    }

    private fun accountDouble(db: SQLiteDatabase, column: String): Double {
        db.rawQuery(
            "SELECT `$column` FROM paper_account WHERE id = 1",
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getDouble(0)
        }
    }

    private fun count(
        db: SQLiteDatabase,
        table: String,
        selection: String? = null
    ): Int {
        val where = selection?.let { " WHERE $it" } ?: ""
        db.rawQuery("SELECT COUNT(*) FROM `$table`$where", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getInt(0)
        }
    }
}
