package com.sb.backend.repositories

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.sb.backend.core.AppConfig
import com.sb.backend.database.SBDatabaseHelper
import com.sb.backend.database.DatabaseMaintenanceGate
import com.sb.backend.models.OrderSide
import com.sb.backend.models.OrderStatus
import com.sb.backend.models.PaperOrder
import com.sb.backend.models.Position
import java.util.Locale

data class PaperAccount(
    val startingBalance: Double,
    val balance: Double,
    val reservedMargin: Double,
    val realizedPnl: Double,
    val updatedAt: Long
) {
    val availableBalance: Double
        get() = balance - reservedMargin
}

class PaperRepository(
    private val databaseHelper: SBDatabaseHelper,
    private val maintenanceGate: DatabaseMaintenanceGate = DatabaseMaintenanceGate.shared
) {

    fun ensureAccount(startingBalance: Double): PaperAccount {
        return maintenanceGate.withOperation {
        require(startingBalance.isFinite() && startingBalance > 0.0) {
            "Starting balance must be a valid positive amount."
        }

        val existing = getAccount()
        if (existing != null) return existing

        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("id", 1)
            put("starting_balance", startingBalance)
            put("balance", startingBalance)
            put("reserved_margin", 0.0)
            put("realized_pnl", 0.0)
            put("updated_at", now)
        }

        databaseHelper.writableDatabase.insertWithOnConflict(
            SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )

        return PaperAccount(
            startingBalance = startingBalance,
            balance = startingBalance,
            reservedMargin = 0.0,
            realizedPnl = 0.0,
            updatedAt = now
        )
    
        }
    }

    fun hasAccount(): Boolean = maintenanceGate.withOperation { getAccount() != null }

    fun getAccount(): PaperAccount? {
        return maintenanceGate.withOperation {
        databaseHelper.readableDatabase.query(
            SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
            accountProjection,
            "id = ?",
            arrayOf("1"),
            null,
            null,
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) readAccount(cursor) else null
        }
    
        }
    }

    fun setStartingBalance(startingBalance: Double): PaperAccount {
        return maintenanceGate.withOperation {
        require(startingBalance.isFinite() && startingBalance > 0.0) {
            "Starting balance must be a valid positive amount."
        }

        val db = databaseHelper.writableDatabase
        db.beginTransaction()
        try {
            check(countOrders(db) == 0) {
                "Starting balance cannot be changed after paper orders exist."
            }
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("id", 1)
                put("starting_balance", startingBalance)
                put("balance", startingBalance)
                put("reserved_margin", 0.0)
                put("realized_pnl", 0.0)
                put("updated_at", now)
            }
            db.insertWithOnConflict(
                SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            db.setTransactionSuccessful()
            return PaperAccount(
                startingBalance = startingBalance,
                balance = startingBalance,
                reservedMargin = 0.0,
                realizedPnl = 0.0,
                updatedAt = now
            )
        } finally {
            db.endTransaction()
        }
    
        }
    }

    fun saveOrder(
        symbol: String,
        exchange: String,
        side: String,
        quantity: Int,
        entryPrice: Double,
        note: String = ""
    ): PaperOrder {
        return maintenanceGate.withOperation {
        require(symbol.isNotBlank()) { "Symbol cannot be empty." }
        require(quantity > 0) { "Quantity must be greater than zero." }
        require(entryPrice.isFinite() && entryPrice > 0.0) {
            "Entry price must be valid and positive."
        }

        val normalizedSide = normalizeSide(side)
        val normalizedSymbol = symbol.trim().toUpperCase(Locale.ROOT)
        val normalizedExchange = exchange.trim().toUpperCase(Locale.ROOT)
        val orderValue = quantity.toDouble() * entryPrice
        require(orderValue.isFinite()) { "Order value is too large." }

        val marginRate = AppConfig.get().shortMarginRate
        val reserved = if (normalizedSide == OrderSide.SELL.name) {
            orderValue * marginRate
        } else {
            0.0
        }
        val cashDebit = if (normalizedSide == OrderSide.BUY.name) {
            orderValue
        } else {
            0.0
        }

        val db = databaseHelper.writableDatabase
        db.beginTransaction()
        try {
            val account = queryAccount(db)
                ?: throw IllegalStateException("Set starting balance first.")
            val currentReserved = queryReserved(db)
            val available = account.balance - currentReserved
            val required = cashDebit + reserved

            check(available + 0.000001 >= required) {
                "Insufficient available funds. Required: ${
                    formatAmount(required)
                }"
            }

            val now = System.currentTimeMillis()
            val orderValues = ContentValues().apply {
                put("symbol", normalizedSymbol)
                put("exchange", normalizedExchange)
                put("side", normalizedSide)
                put("quantity", quantity)
                put("entry_price", entryPrice)
                put("reserved_margin", reserved)
                put("status", OrderStatus.OPEN.name)
                put("note", note.trim())
                put("created_at", now)
                put("updated_at", now)
            }

            val orderId = db.insertOrThrow(
                SBDatabaseHelper.TABLE_PAPER_ORDERS,
                null,
                orderValues
            )

            val positionValues = ContentValues().apply {
                put("order_id", orderId)
                put("symbol", normalizedSymbol)
                put("exchange", normalizedExchange)
                put("side", normalizedSide)
                put("quantity", quantity)
                put("entry_price", entryPrice)
                put("last_price", entryPrice)
                put("reserved_margin", reserved)
                put("status", OrderStatus.OPEN.name)
                put("opened_at", now)
                put("updated_at", now)
            }
            db.insertOrThrow(
                SBDatabaseHelper.TABLE_POSITIONS,
                null,
                positionValues
            )

            val accountValues = ContentValues().apply {
                put("balance", account.balance - cashDebit)
                put("reserved_margin", currentReserved + reserved)
                put("updated_at", now)
            }
            check(
                db.update(
                    SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
                    accountValues,
                    "id = ?",
                    arrayOf("1")
                ) == 1
            ) { "Could not update paper account." }

            db.setTransactionSuccessful()

            return PaperOrder(
                id = orderId,
                symbol = normalizedSymbol,
                exchange = normalizedExchange,
                side = normalizedSide,
                quantity = quantity,
                entryPrice = entryPrice,
                reservedMargin = reserved,
                status = OrderStatus.OPEN.name,
                note = note.trim(),
                createdAt = now,
                updatedAt = now
            )
        } finally {
            db.endTransaction()
        }
    
        }
    }

    fun closeOrder(orderId: Long, exitPrice: Double): PaperOrder? {
        return maintenanceGate.withOperation {
        require(orderId > 0L) { "Invalid order ID." }
        require(exitPrice.isFinite() && exitPrice > 0.0) {
            "Exit price must be valid and positive."
        }

        val db = databaseHelper.writableDatabase
        db.beginTransaction()
        try {
            val order = queryOrder(db, orderId) ?: return null
            if (!order.isOpen) return null

            val orderValue = order.quantity.toDouble() * order.entryPrice
            val exitValue = order.quantity.toDouble() * exitPrice
            val pnl = if (order.side == OrderSide.BUY.name) {
                exitValue - orderValue
            } else {
                orderValue - exitValue
            }
            require(pnl.isFinite() && exitValue.isFinite()) {
                "Calculated trade result is invalid."
            }

            val account = queryAccount(db)
                ?: throw IllegalStateException("Paper account is missing.")

            val cashChange = if (order.side == OrderSide.BUY.name) {
                exitValue
            } else {
                pnl
            }
            val newCash = account.balance + cashChange
            check(newCash.isFinite()) { "Calculated cash balance is invalid." }

            val now = System.currentTimeMillis()
            val orderValues = ContentValues().apply {
                put("exit_price", exitPrice)
                put("realized_pnl", pnl)
                put("status", OrderStatus.CLOSED.name)
                put("reserved_margin", 0.0)
                put("updated_at", now)
            }
            val updated = db.update(
                SBDatabaseHelper.TABLE_PAPER_ORDERS,
                orderValues,
                "id = ? AND status = ?",
                arrayOf(orderId.toString(), OrderStatus.OPEN.name)
            )
            if (updated != 1) return null

            val positionValues = ContentValues().apply {
                put("last_price", exitPrice)
                put("reserved_margin", 0.0)
                put("status", OrderStatus.CLOSED.name)
                put("updated_at", now)
            }
            db.update(
                SBDatabaseHelper.TABLE_POSITIONS,
                positionValues,
                "order_id = ? AND status = ?",
                arrayOf(orderId.toString(), OrderStatus.OPEN.name)
            )

            val remainingReserved = queryReserved(db)
            val accountValues = ContentValues().apply {
                put("balance", newCash)
                put("reserved_margin", remainingReserved)
                put("realized_pnl", account.realizedPnl + pnl)
                put("updated_at", now)
            }
            check(
                db.update(
                    SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
                    accountValues,
                    "id = ?",
                    arrayOf("1")
                ) == 1
            ) { "Could not settle paper trade." }

            db.setTransactionSuccessful()

            return order.copy(
                exitPrice = exitPrice,
                realizedPnl = pnl,
                reservedMargin = 0.0,
                status = OrderStatus.CLOSED.name,
                updatedAt = now
            )
        } finally {
            db.endTransaction()
        }
    
        }
    }

    fun getOrder(orderId: Long): PaperOrder? {
        return maintenanceGate.withOperation {
        return queryOrder(databaseHelper.readableDatabase, orderId)
    
        }
    }

    fun getOrders(status: String? = null): List<PaperOrder> {
        return maintenanceGate.withOperation {
        val selection: String?
        val args: Array<String>?
        if (status == null) {
            selection = null
            args = null
        } else {
            val normalized = normalizeStatus(status)
            selection = "status = ?"
            args = arrayOf(normalized)
        }

        val result = ArrayList<PaperOrder>()
        databaseHelper.readableDatabase.query(
            SBDatabaseHelper.TABLE_PAPER_ORDERS,
            orderProjection,
            selection,
            args,
            null,
            null,
            "created_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(readOrder(cursor))
            }
        }
        return result
    
        }
    }

    fun getOpenOrders(): List<PaperOrder> {
        return maintenanceGate.withOperation {
        return getOrders(OrderStatus.OPEN.name)
    
        }
    }

    fun getPositions(openOnly: Boolean = true): List<Position> {
        return maintenanceGate.withOperation {
        val selection = if (openOnly) "status = ?" else null
        val args = if (openOnly) {
            arrayOf(OrderStatus.OPEN.name)
        } else {
            null
        }

        val result = ArrayList<Position>()
        databaseHelper.readableDatabase.query(
            SBDatabaseHelper.TABLE_POSITIONS,
            positionProjection,
            selection,
            args,
            null,
            null,
            "opened_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(readPosition(cursor))
            }
        }
        return result
    
        }
    }

    fun updatePositionPrices(prices: Map<String, Double>): Int {
        return maintenanceGate.withOperation {
        if (prices.isEmpty()) return 0
        val db = databaseHelper.writableDatabase
        var updated = 0
        val now = System.currentTimeMillis()

        db.beginTransaction()
        try {
            prices.forEach { entry ->
                val symbol = entry.key.trim().toUpperCase(Locale.ROOT)
                val price = entry.value
                if (symbol.isBlank() ||
                    !price.isFinite() ||
                    price <= 0.0
                ) {
                    return@forEach
                }

                val values = ContentValues().apply {
                    put("last_price", price)
                    put("updated_at", now)
                }
                updated += db.update(
                    SBDatabaseHelper.TABLE_POSITIONS,
                    values,
                    "UPPER(symbol) = ? AND status = ?",
                    arrayOf(symbol, OrderStatus.OPEN.name)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return updated
    
        }
    }

    fun getReservedMargin(): Double {
        return maintenanceGate.withOperation {
        return databaseHelper.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(reserved_margin), 0) " +
                "FROM ${SBDatabaseHelper.TABLE_POSITIONS} WHERE status = ?",
            arrayOf(OrderStatus.OPEN.name)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
        }
    
        }
    }

    fun getRealizedPnl(): Double {
        return maintenanceGate.withOperation {
        return databaseHelper.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(realized_pnl), 0) " +
                "FROM ${SBDatabaseHelper.TABLE_PAPER_ORDERS} " +
                "WHERE status = ?",
            arrayOf(OrderStatus.CLOSED.name)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
        }
    
        }
    }

    fun getUnrealizedPnl(): Double {
        return maintenanceGate.withOperation {
        var total = 0.0
        getPositions(true).forEach { position ->
            total += position.unrealizedPnl
        }
        return total
    
        }
    }

    fun getOpenPositionCount(): Int {
        return maintenanceGate.withOperation {
        databaseHelper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${SBDatabaseHelper.TABLE_POSITIONS} " +
                "WHERE status = ?",
            arrayOf(OrderStatus.OPEN.name)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    
        }
    }

    fun reset(startingBalance: Double): PaperAccount {
        return maintenanceGate.withOperation {
        require(startingBalance.isFinite() && startingBalance > 0.0) {
            "Starting balance must be a valid positive amount."
        }

        val db = databaseHelper.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.delete(SBDatabaseHelper.TABLE_POSITIONS, null, null)
            db.delete(SBDatabaseHelper.TABLE_PAPER_ORDERS, null, null)

            val values = ContentValues().apply {
                put("id", 1)
                put("starting_balance", startingBalance)
                put("balance", startingBalance)
                put("reserved_margin", 0.0)
                put("realized_pnl", 0.0)
                put("updated_at", now)
            }
            db.insertWithOnConflict(
                SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return PaperAccount(
            startingBalance = startingBalance,
            balance = startingBalance,
            reservedMargin = 0.0,
            realizedPnl = 0.0,
            updatedAt = now
        )
    
        }
    }

    private fun queryOrder(db: SQLiteDatabase, orderId: Long): PaperOrder? {
        db.query(
            SBDatabaseHelper.TABLE_PAPER_ORDERS,
            orderProjection,
            "id = ?",
            arrayOf(orderId.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) readOrder(cursor) else null
        }
    }

    private fun queryAccount(db: SQLiteDatabase): PaperAccount? {
        db.query(
            SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
            accountProjection,
            "id = ?",
            arrayOf("1"),
            null,
            null,
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) readAccount(cursor) else null
        }
    }

    private fun queryReserved(db: SQLiteDatabase): Double {
        db.rawQuery(
            "SELECT COALESCE(SUM(reserved_margin), 0) " +
                "FROM ${SBDatabaseHelper.TABLE_POSITIONS} WHERE status = ?",
            arrayOf(OrderStatus.OPEN.name)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
        }
    }

    private fun countOrders(db: SQLiteDatabase): Int {
        db.rawQuery(
            "SELECT COUNT(*) FROM ${SBDatabaseHelper.TABLE_PAPER_ORDERS}",
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun readAccount(cursor: Cursor): PaperAccount {
        return PaperAccount(
            startingBalance = cursor.getDouble(0),
            balance = cursor.getDouble(1),
            reservedMargin = cursor.getDouble(2),
            realizedPnl = cursor.getDouble(3),
            updatedAt = cursor.getLong(4)
        )
    }

    private fun readOrder(cursor: Cursor): PaperOrder {
        return PaperOrder(
            id = cursor.getLong(0),
            symbol = cursor.getString(1),
            exchange = cursor.getString(2),
            side = cursor.getString(3),
            quantity = cursor.getInt(4),
            entryPrice = cursor.getDouble(5),
            exitPrice = if (cursor.isNull(6)) null else cursor.getDouble(6),
            realizedPnl = if (cursor.isNull(7)) null else cursor.getDouble(7),
            reservedMargin = cursor.getDouble(8),
            status = cursor.getString(9),
            note = cursor.getString(10),
            createdAt = cursor.getLong(11),
            updatedAt = cursor.getLong(12)
        )
    }

    private fun readPosition(cursor: Cursor): Position {
        return Position(
            id = cursor.getLong(0),
            orderId = cursor.getLong(1),
            symbol = cursor.getString(2),
            exchange = cursor.getString(3),
            side = cursor.getString(4),
            quantity = cursor.getInt(5),
            entryPrice = cursor.getDouble(6),
            lastPrice = cursor.getDouble(7),
            reservedMargin = cursor.getDouble(8),
            status = cursor.getString(9),
            openedAt = cursor.getLong(10),
            updatedAt = cursor.getLong(11)
        )
    }

    private fun normalizeSide(side: String): String {
        val normalized = side.trim().toUpperCase(Locale.ROOT)
        require(
            normalized == OrderSide.BUY.name ||
                normalized == OrderSide.SELL.name
        ) {
            "Side must be BUY or SELL."
        }
        return normalized
    }

    private fun normalizeStatus(status: String): String {
        val normalized = status.trim().toUpperCase(Locale.ROOT)
        require(
            normalized == OrderStatus.OPEN.name ||
                normalized == OrderStatus.CLOSED.name
        ) {
            "Status must be OPEN or CLOSED."
        }
        return normalized
    }

    private fun formatAmount(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }

    companion object {
        private val accountProjection = arrayOf(
            "starting_balance",
            "balance",
            "reserved_margin",
            "realized_pnl",
            "updated_at"
        )

        private val orderProjection = arrayOf(
            "id",
            "symbol",
            "exchange",
            "side",
            "quantity",
            "entry_price",
            "exit_price",
            "realized_pnl",
            "reserved_margin",
            "status",
            "note",
            "created_at",
            "updated_at"
        )

        private val positionProjection = arrayOf(
            "id",
            "order_id",
            "symbol",
            "exchange",
            "side",
            "quantity",
            "entry_price",
            "last_price",
            "reserved_margin",
            "status",
            "opened_at",
            "updated_at"
        )
    }
}
