package com.sb

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DashboardDbHelper(context: Context) :
    SQLiteOpenHelper(context, "sb_bot_local.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        createDashboardTable(db)
        createPaperTables(db)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // Existing dashboard data is preserved.
        if (oldVersion < 2) {
            createPaperTables(db)
        }
    }

    private fun createDashboardTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS dashboard_values (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createPaperTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS paper_account (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                balance REAL NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS paper_orders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                symbol TEXT NOT NULL,
                side TEXT NOT NULL,
                quantity INTEGER NOT NULL CHECK (quantity > 0),
                entry_price REAL NOT NULL CHECK (entry_price > 0),
                exit_price REAL,
                realized_pnl REAL,
                status TEXT NOT NULL DEFAULT 'OPEN',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_paper_orders_status
            ON paper_orders(status)
            """.trimIndent()
        )
    }

    fun saveValue(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
            put("updated_at", System.currentTimeMillis())
        }

        writableDatabase.insertWithOnConflict(
            "dashboard_values",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getValue(key: String): String? {
        readableDatabase.query(
            "dashboard_values",
            arrayOf("value"),
            "key = ?",
            arrayOf(key),
            null,
            null,
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                null
            }
        }
    }

    /**
     * Save the paper balance only when explicitly supplied.
     * This method does not invent an initial balance.
     */
    fun savePaperBalance(balance: Double) {
        require(balance.isFinite() && balance >= 0.0) {
            "Paper balance must be a valid non-negative amount."
        }

        val values = ContentValues().apply {
            put("id", 1)
            put("balance", balance)
            put("updated_at", System.currentTimeMillis())
        }

        writableDatabase.insertWithOnConflict(
            "paper_account",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getPaperBalance(): Double? {
        readableDatabase.query(
            "paper_account",
            arrayOf("balance"),
            "id = ?",
            arrayOf("1"),
            null,
            null,
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                cursor.getDouble(0)
            } else {
                null
            }
        }
    }

    /**
     * Store a paper order locally.
     * This does not send an order to a broker.
     */
    fun savePaperOrder(
        symbol: String,
        side: String,
        quantity: Int,
        entryPrice: Double
    ): Long {
        require(symbol.isNotBlank()) {
            "Symbol cannot empty."
        }
        require(side.equals("BUY", true) || side.equals("SELL", true)) {
            "Side must be BUY or SELL."
        }
        require(quantity > 0) {
            "Quantity must greater than zero."
        }
        require(entryPrice.isFinite() && entryPrice > 0.0) {
            "Entry price must be valid positive amount."
        }

        val now = System.currentTimeMillis()

        val values = ContentValues().apply {
            put("symbol", symbol.trim().toUpperCase(java.util.Locale.ROOT))
            put("side", side.toUpperCase(java.util.Locale.ROOT))
            put("quantity", quantity)
            put("entry_price", entryPrice)
            put("status", "OPEN")
            put("created_at", now)
            put("updated_at", now)
        }

        return writableDatabase.insertOrThrow(
            "paper_orders",
            null,
            values
        )
    }

    /**
     * Close a locally stored paper order and save its result.
     * This method does not execute a real trade.
     */
    fun closePaperOrder(
        orderId: Long,
        exitPrice: Double,
        realizedPnl: Double
    ): Int {
        require(orderId > 0L) {
            "Invalid order ID."
        }
        require(exitPrice.isFinite() && exitPrice > 0.0) {
            "Exit price must be valid positive amount."
        }
        require(realizedPnl.isFinite()) {
            "P&L must be valid number."
        }

        val values = ContentValues().apply {
            put("exit_price", exitPrice)
            put("realized_pnl", realizedPnl)
            put("status", "CLOSED")
            put("updated_at", System.currentTimeMillis())
        }

        return writableDatabase.update(
            "paper_orders",
            values,
            "id = ? AND status = ?",
            arrayOf(orderId.toString(), "OPEN")
        )
    }

    fun getPaperOrders(
        status: String? = null
    ): List<PaperOrder> {
        val result = mutableListOf<PaperOrder>()

        val selection = if (status == null) {
            null
        } else {
            require(
                status == "OPEN" ||
                    status == "CLOSED"
            ) {
                "Status must be OPEN or CLOSED."
            }
            "status = ?"
        }

        val selectionArgs = if (status == null) {
            null
        } else {
            arrayOf(status)
        }

        readableDatabase.query(
            "paper_orders",
            arrayOf(
                "id",
                "symbol",
                "side",
                "quantity",
                "entry_price",
                "exit_price",
                "realized_pnl",
                "status",
                "created_at",
                "updated_at"
            ),
            selection,
            selectionArgs,
            null,
            null,
            "created_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    PaperOrder(
                        id = cursor.getLong(0),
                        symbol = cursor.getString(1),
                        side = cursor.getString(2),
                        quantity = cursor.getInt(3),
                        entryPrice = cursor.getDouble(4),
                        exitPrice = if (cursor.isNull(5)) {
                            null
                        } else {
                            cursor.getDouble(5)
                        },
                        realizedPnl = if (cursor.isNull(6)) {
                            null
                        } else {
                            cursor.getDouble(6)
                        },
                        status = cursor.getString(7),
                        createdAt = cursor.getLong(8),
                        updatedAt = cursor.getLong(9)
                    )
                )
            }
        }

        return result
    }
}

data class PaperOrder(
    val id: Long,
    val symbol: String,
    val side: String,
    val quantity: Int,
    val entryPrice: Double,
    val exitPrice: Double?,
    val realizedPnl: Double?,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long
)
