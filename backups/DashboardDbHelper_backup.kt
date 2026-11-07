
package com.sb

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

class DashboardDbHelper(context: Context) :
    SQLiteOpenHelper(context, "sb_bot_local.db", null, 4) {

    companion object {
        const val SHORT_MARGIN_RATE = 0.20
    }

    override fun onCreate(db: SQLiteDatabase) {
        createDashboardTable(db)
        createPaperTables(db)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        createDashboardTable(db)
        createPaperTables(db)

        if (!columnExists(db, "paper_orders", "reserved_margin")) {
            db.execSQL(
                "ALTER TABLE paper_orders ADD COLUMN reserved_margin REAL NOT NULL DEFAULT 0"
            )
        }

        if (oldVersion < 4) {
            db.beginTransaction()
            try {
                // Legacy BUY orders had not deducted cash. Debit them once.
                db.execSQL(
                    """
                    UPDATE paper_account
                    SET balance = balance - COALESCE((
                        SELECT SUM(quantity * entry_price)
                        FROM paper_orders
                        WHERE status = 'OPEN' AND UPPER(side) = 'BUY'
                    ), 0),
                    updated_at = ${System.currentTimeMillis()}
                    WHERE id = 1
                    """.trimIndent()
                )

                // BUY cost is debited from cash; it must not also be reserved.
                db.execSQL(
                    """
                    UPDATE paper_orders
                    SET reserved_margin = 0
                    WHERE status = 'OPEN' AND UPPER(side) = 'BUY'
                    """.trimIndent()
                )

                // Short sale proceeds are excluded from cash.
                // Reserve only the defined 20% margin.
                db.execSQL(
                    """
                    UPDATE paper_orders
                    SET reserved_margin = entry_price * quantity * $SHORT_MARGIN_RATE
                    WHERE status = 'OPEN' AND UPPER(side) = 'SELL'
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    UPDATE paper_orders
                    SET reserved_margin = 0
                    WHERE status = 'CLOSED'
                    """.trimIndent()
                )

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override fun onOpen(db: android.database.sqlite.SQLiteDatabase) {
        super.onOpen(db)

        com.sb.backend.database.BackendSchema.create(db)
    }

    private fun columnExists(
        db: SQLiteDatabase,
        table: String,
        column: String
    ): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val index = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(index) == column) return true
            }
        }
        return false
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
                updated_at INTEGER NOT NULL,
                reserved_margin REAL NOT NULL DEFAULT 0
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
            "dashboard_values", null, values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getValue(key: String): String? {
        readableDatabase.query(
            "dashboard_values", arrayOf("value"),
            "key = ?", arrayOf(key), null, null, null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun savePaperBalance(balance: Double) {
        require(balance.isFinite() && balance > 0.0) {
            "Starting balance must be a valid positive amount."
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            check(countOrders(db) == 0) {
                "Starting balance cannot be changed after paper orders exist."
            }

            val values = ContentValues().apply {
                put("id", 1)
                put("balance", balance)
                put("updated_at", System.currentTimeMillis())
            }

            db.insertWithOnConflict(
                "paper_account", null, values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getPaperBalance(): Double? {
        readableDatabase.query(
            "paper_account", arrayOf("balance"),
            "id = ?", arrayOf("1"), null, null, null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getDouble(0) else null
        }
    }

    fun getReservedMargin(): Double {
        readableDatabase.rawQuery(
            """
            SELECT COALESCE(SUM(reserved_margin), 0)
            FROM paper_orders WHERE status = 'OPEN'
            """.trimIndent(),
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
        }
    }

    fun getAvailableBalance(): Double? {
        val cash = getPaperBalance() ?: return null
        return cash - getReservedMargin()
    }

    private fun countOrders(db: SQLiteDatabase): Int {
        db.rawQuery("SELECT COUNT(*) FROM paper_orders", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun queryBalance(db: SQLiteDatabase): Double? {
        db.query(
            "paper_account", arrayOf("balance"),
            "id = ?", arrayOf("1"), null, null, null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getDouble(0) else null
        }
    }

    private fun queryReserved(db: SQLiteDatabase): Double {
        db.rawQuery(
            """
            SELECT COALESCE(SUM(reserved_margin), 0)
            FROM paper_orders WHERE status = 'OPEN'
            """.trimIndent(),
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
        }
    }

    fun savePaperOrder(
        symbol: String,
        side: String,
        quantity: Int,
        entryPrice: Double
    ): Long {
        require(symbol.isNotBlank()) { "Symbol cannot be empty." }
        require(side.equals("BUY", true) || side.equals("SELL", true)) {
            "Side must be BUY or SELL."
        }
        require(quantity > 0) { "Quantity must be greater than zero." }
        require(entryPrice.isFinite() && entryPrice > 0.0) {
            "Entry price must be valid and positive."
        }

        val normalizedSide = side.trim().toUpperCase(Locale.ROOT)
        val orderValue = quantity.toDouble() * entryPrice
        require(orderValue.isFinite()) { "Order value is too large." }

        // BUY spends full purchase cost. Short SELL reserves 20% margin.
        val reserve = if (normalizedSide == "BUY") {
            0.0
        } else {
            orderValue * SHORT_MARGIN_RATE
        }
        val cashDebit = if (normalizedSide == "BUY") orderValue else 0.0

        val db = writableDatabase
        db.beginTransaction()
        try {
            val cash = queryBalance(db)
                ?: throw IllegalStateException("Set starting balance first.")
            val available = cash - queryReserved(db)

            check(available + 0.000001 >= cashDebit + reserve) {
                "Insufficient available funds. Required: ${
                    "%.2f".format(Locale.US, cashDebit + reserve)
                }"
            }

            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("symbol", symbol.trim().toUpperCase(Locale.ROOT))
                put("side", normalizedSide)
                put("quantity", quantity)
                put("entry_price", entryPrice)
                put("status", "OPEN")
                put("reserved_margin", reserve)
                put("created_at", now)
                put("updated_at", now)
            }

            val orderId = db.insertOrThrow("paper_orders", null, values)

            if (cashDebit > 0.0) {
                val accountValues = ContentValues().apply {
                    put("balance", cash - cashDebit)
                    put("updated_at", now)
                }
                check(
                    db.update(
                        "paper_account", accountValues,
                        "id = ?", arrayOf("1")
                    ) == 1
                ) { "Could not debit BUY cost." }
            }

            db.setTransactionSuccessful()
            return orderId
        } finally {
            db.endTransaction()
        }
    }

    /**
     * BUY: credit sale proceeds; entry cost was already debited.
     * SELL short: add realized P&L; short proceeds were never counted as cash.
     * The order and account update commit atomically.
     */
    fun closePaperOrder(orderId: Long, exitPrice: Double): Double? {
        require(orderId > 0L) { "Invalid order ID." }
        require(exitPrice.isFinite() && exitPrice > 0.0) {
            "Exit price must be valid and positive."
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            var side = ""
            var quantity = 0
            var entryPrice = 0.0

            db.query(
                "paper_orders",
                arrayOf("side", "quantity", "entry_price"),
                "id = ? AND status = ?",
                arrayOf(orderId.toString(), "OPEN"),
                null, null, null
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                side = cursor.getString(0)
                quantity = cursor.getInt(1)
                entryPrice = cursor.getDouble(2)
            }

            val orderValue = quantity.toDouble() * entryPrice
            val exitValue = quantity.toDouble() * exitPrice
            val pnl = if (side == "BUY") {
                exitValue - orderValue
            } else {
                orderValue - exitValue
            }

            require(pnl.isFinite() && exitValue.isFinite()) {
                "Calculated trade result is invalid."
            }

            val currentCash = queryBalance(db)
                ?: throw IllegalStateException("Paper account balance is missing.")

            // BUY: credit sale proceeds. SELL: credit profit/loss only.
            val cashChange = if (side == "BUY") exitValue else pnl
            val newCash = currentCash + cashChange
            check(newCash.isFinite()) { "Calculated cash balance is invalid." }

            val now = System.currentTimeMillis()
            val orderValues = ContentValues().apply {
                put("exit_price", exitPrice)
                put("realized_pnl", pnl)
                put("status", "CLOSED")
                put("reserved_margin", 0.0)
                put("updated_at", now)
            }

            val updated = db.update(
                "paper_orders", orderValues,
                "id = ? AND status = ?",
                arrayOf(orderId.toString(), "OPEN")
            )
            if (updated != 1) return null

            val accountValues = ContentValues().apply {
                put("balance", newCash)
                put("updated_at", now)
            }
            check(
                db.update(
                    "paper_account", accountValues,
                    "id = ?", arrayOf("1")
                ) == 1
            ) { "Could not settle paper trade." }

            db.setTransactionSuccessful()
            return pnl
        } finally {
            db.endTransaction()
        }
    }

    fun getPaperOrders(status: String? = null): List<PaperOrder> {
        val result = mutableListOf<PaperOrder>()

        val selection = if (status == null) null else {
            require(status == "OPEN" || status == "CLOSED") {
                "Status must be OPEN or CLOSED."
            }
            "status = ?"
        }
        val args = if (status == null) null else arrayOf(status)

        readableDatabase.query(
            "paper_orders",
            arrayOf(
                "id", "symbol", "side", "quantity", "entry_price",
                "exit_price", "realized_pnl", "status",
                "created_at", "updated_at"
            ),
            selection, args, null, null, "created_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    PaperOrder(
                        id = cursor.getLong(0),
                        symbol = cursor.getString(1),
                        side = cursor.getString(2),
                        quantity = cursor.getInt(3),
                        entryPrice = cursor.getDouble(4),
                        exitPrice = if (cursor.isNull(5)) null else cursor.getDouble(5),
                        realizedPnl = if (cursor.isNull(6)) null else cursor.getDouble(6),
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