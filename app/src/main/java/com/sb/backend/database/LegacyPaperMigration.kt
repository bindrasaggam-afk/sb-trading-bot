package com.sb.backend.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.sb.backend.core.AppConfig
import java.util.Locale

/**
 * One-way, transactional migration of the legacy DashboardDbHelper paper ledger
 * (sb_bot_local.db) into the canonical backend database (sb_backend.db).
 *
 * The legacy database is opened read-only and is never modified or deleted.
 * A completed migration is recorded in app_state, and every migrated order
 * carries its legacy id so reruns cannot create duplicates.
 */
object LegacyPaperMigration {

    const val LEGACY_DATABASE_NAME = "sb_bot_local.db"
    const val MARKER_KEY = "paper_local_migration_v1"

    private const val EPSILON = 0.000001
    private const val DEFAULT_EXCHANGE = "NSE"
    private const val MIGRATION_NOTE = "Migrated from sb_bot_local.db"

    data class Result(
        val applied: Boolean,
        val skipped: Boolean,
        val legacyOrders: Int,
        val insertedOrders: Int,
        val ensuredPositions: Int,
        val message: String
    )

    fun migrate(
        context: Context,
        mainDb: SQLiteDatabase,
        now: Long = System.currentTimeMillis()
    ): Result {
        if (readMarker(mainDb) != null) {
            return Result(
                applied = false,
                skipped = true,
                legacyOrders = 0,
                insertedOrders = 0,
                ensuredPositions = 0,
                message = "Legacy paper migration already completed."
            )
        }

        val legacyFile = context.getDatabasePath(LEGACY_DATABASE_NAME)
        if (!legacyFile.exists()) {
            return Result(
                applied = false,
                skipped = true,
                legacyOrders = 0,
                insertedOrders = 0,
                ensuredPositions = 0,
                message = "Legacy paper database not found."
            )
        }

        val legacyDb = SQLiteDatabase.openDatabase(
            legacyFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

        return try {
            migrate(
                legacyDb = legacyDb,
                mainDb = mainDb,
                defaultExchange = AppConfig.get().defaultExchange,
                shortMarginRate = AppConfig.get().shortMarginRate,
                now = now
            )
        } finally {
            legacyDb.close()
        }
    }

    fun migrate(
        legacyDb: SQLiteDatabase,
        mainDb: SQLiteDatabase,
        defaultExchange: String = DEFAULT_EXCHANGE,
        shortMarginRate: Double = 0.20,
        now: Long = System.currentTimeMillis()
    ): Result {
        if (readMarker(mainDb) != null) {
            return Result(
                applied = false,
                skipped = true,
                legacyOrders = 0,
                insertedOrders = 0,
                ensuredPositions = 0,
                message = "Legacy paper migration already completed."
            )
        }

        val legacyBalance = readLegacyBalance(legacyDb)
        val legacyOrders = readLegacyOrders(legacyDb, shortMarginRate)

        if (legacyOrders.isEmpty() && legacyBalance == null) {
            return Result(
                applied = false,
                skipped = true,
                legacyOrders = 0,
                insertedOrders = 0,
                ensuredPositions = 0,
                message = "Legacy paper database has no account or orders."
            )
        }

        if (legacyOrders.isNotEmpty() && legacyBalance == null) {
            throw IllegalStateException(
                "Legacy paper orders exist but the legacy account is missing."
            )
        }

        val normalizedExchange = defaultExchange
            .trim()
            .uppercase(Locale.ROOT)
            .ifBlank { DEFAULT_EXCHANGE }

        val legacyRealized = legacyOrders
            .filter { it.status == "CLOSED" }
            .sumOf { it.realizedPnl ?: 0.0 }
        val legacyReserved = legacyOrders
            .filter { it.status == "OPEN" }
            .sumOf { it.reservedMargin }
        val legacyOpenBuyCost = legacyOrders
            .filter { it.status == "OPEN" && it.side == "BUY" }
            .sumOf { it.quantity.toDouble() * it.entryPrice }
        val legacyStartingBalance = (legacyBalance ?: 0.0) -
            legacyRealized +
            legacyOpenBuyCost

        require(legacyStartingBalance.isFinite()) {
            "Legacy starting balance is invalid."
        }

        mainDb.beginTransaction()
        try {
            if (readMarker(mainDb) != null) {
                return Result(
                    applied = false,
                    skipped = true,
                    legacyOrders = legacyOrders.size,
                    insertedOrders = 0,
                    ensuredPositions = 0,
                    message = "Legacy paper migration already completed."
                )
            }

            val previouslyMigrated = countMigratedOrders(mainDb)
            val mainAccount = readMainAccount(mainDb)
            val mainOrderCount = countTableRows(mainDb, "paper_orders")
            val accountMode = when {
                mainAccount == null -> AccountMode.REPLACE
                previouslyMigrated > 0 -> AccountMode.KEEP
                mainOrderCount > 0 -> AccountMode.MERGE
                else -> AccountMode.REPLACE
            }

            var insertedOrders = 0
            var ensuredPositions = 0
            val migratedOrderIds = ArrayList<Long>(legacyOrders.size)

            legacyOrders.forEach { legacy ->
                val existingOrderId = findOrderIdByLegacyId(
                    mainDb,
                    legacy.id
                )

                val orderId = if (existingOrderId != null) {
                    existingOrderId
                } else {
                    val values = ContentValues().apply {
                        put("symbol", legacy.symbol)
                        put("exchange", normalizedExchange)
                        put("side", legacy.side)
                        put("quantity", legacy.quantity)
                        put("entry_price", legacy.entryPrice)
                        put("exit_price", legacy.exitPrice)
                        put("realized_pnl", legacy.realizedPnl)
                        put("reserved_margin", legacy.reservedMargin)
                        put("status", legacy.status)
                        put("note", MIGRATION_NOTE)
                        put("stop_loss", null as Double?)
                        put("target", null as Double?)
                        put("legacy_order_id", legacy.id)
                        put("created_at", legacy.createdAt)
                        put("updated_at", legacy.updatedAt)
                    }
                    val newId = mainDb.insertOrThrow("paper_orders", null, values)
                    insertedOrders++
                    newId
                }

                migratedOrderIds.add(orderId)

                if (ensurePosition(
                        mainDb = mainDb,
                        orderId = orderId,
                        legacy = legacy,
                        exchange = normalizedExchange
                    )
                ) {
                    ensuredPositions++
                }
            }

            val account = applyAccount(
                mainDb = mainDb,
                mode = accountMode,
                mainAccount = mainAccount,
                legacyStartingBalance = legacyStartingBalance,
                legacyBalance = legacyBalance ?: 0.0,
                legacyReserved = legacyReserved,
                legacyRealized = legacyRealized,
                now = now
            )

            verifyMigration(
                mainDb = mainDb,
                legacyOrderCount = legacyOrders.size,
                migratedOrderIds = migratedOrderIds,
                account = account
            )

            val markerValue = buildString {
                append("v1")
                append("|legacyOrders=").append(legacyOrders.size)
                append("|inserted=").append(insertedOrders)
                append("|positions=").append(ensuredPositions)
                append("|starting=").append(account.startingBalance)
                append("|balance=").append(account.balance)
                append("|reserved=").append(account.reservedMargin)
                append("|realized=").append(account.realizedPnl)
            }
            writeMarker(mainDb, markerValue, now)

            mainDb.setTransactionSuccessful()

            return Result(
                applied = true,
                skipped = false,
                legacyOrders = legacyOrders.size,
                insertedOrders = insertedOrders,
                ensuredPositions = ensuredPositions,
                message = "Legacy paper migration completed."
            )
        } finally {
            mainDb.endTransaction()
        }
    }

    fun readMarker(db: SQLiteDatabase): String? {
        if (!tableExists(db, "app_state")) return null
        db.query(
            "app_state",
            arrayOf("value"),
            "key = ?",
            arrayOf(MARKER_KEY),
            null,
            null,
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun applyAccount(
        mainDb: SQLiteDatabase,
        mode: AccountMode,
        mainAccount: AccountSnapshot?,
        legacyStartingBalance: Double,
        legacyBalance: Double,
        legacyReserved: Double,
        legacyRealized: Double,
        now: Long
    ): AccountSnapshot {
        if (mode == AccountMode.KEEP) {
            return mainAccount ?: throw IllegalStateException(
                "Paper account is missing during migration."
            )
        }

        val merged = if (mode == AccountMode.MERGE && mainAccount != null) {
            AccountSnapshot(
                startingBalance = mainAccount.startingBalance +
                    legacyStartingBalance,
                balance = mainAccount.balance + legacyBalance,
                reservedMargin = mainAccount.reservedMargin + legacyReserved,
                realizedPnl = mainAccount.realizedPnl + legacyRealized,
                updatedAt = now
            )
        } else {
            AccountSnapshot(
                startingBalance = legacyStartingBalance,
                balance = legacyBalance,
                reservedMargin = legacyReserved,
                realizedPnl = legacyRealized,
                updatedAt = now
            )
        }

        require(merged.balance.isFinite()) {
            "Merged paper balance is invalid."
        }
        require(merged.reservedMargin.isFinite() && merged.reservedMargin >= 0.0) {
            "Merged reserved margin is invalid."
        }
        require(merged.realizedPnl.isFinite()) {
            "Merged realized P&L is invalid."
        }

        val values = ContentValues().apply {
            put("id", 1)
            put("starting_balance", merged.startingBalance)
            put("balance", merged.balance)
            put("reserved_margin", merged.reservedMargin)
            put("realized_pnl", merged.realizedPnl)
            put("updated_at", merged.updatedAt)
        }
        mainDb.insertWithOnConflict(
            "paper_account",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        return merged
    }

    private fun ensurePosition(
        mainDb: SQLiteDatabase,
        orderId: Long,
        legacy: LegacyOrder,
        exchange: String
    ): Boolean {
        mainDb.query(
            "positions",
            arrayOf("id"),
            "order_id = ?",
            arrayOf(orderId.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) return false
        }

        val lastPrice = if (legacy.status == "CLOSED") {
            legacy.exitPrice ?: legacy.entryPrice
        } else {
            legacy.entryPrice
        }
        val values = ContentValues().apply {
            put("order_id", orderId)
            put("symbol", legacy.symbol)
            put("exchange", exchange)
            put("side", legacy.side)
            put("quantity", legacy.quantity)
            put("entry_price", legacy.entryPrice)
            put("last_price", lastPrice)
            put("reserved_margin", legacy.reservedMargin)
            put("status", legacy.status)
            put("opened_at", legacy.createdAt)
            put("updated_at", legacy.updatedAt)
        }
        mainDb.insertOrThrow("positions", null, values)
        return true
    }

    private fun verifyMigration(
        mainDb: SQLiteDatabase,
        legacyOrderCount: Int,
        migratedOrderIds: List<Long>,
        account: AccountSnapshot
    ) {
        val migratedOrderCount = countMigratedOrders(mainDb)
        require(migratedOrderCount >= legacyOrderCount) {
            "Migration verification failed: expected $legacyOrderCount " +
                "legacy orders, found $migratedOrderCount."
        }

        val positionCount = countPositionsForOrders(mainDb, migratedOrderIds)
        require(positionCount == migratedOrderIds.size) {
            "Migration verification failed: expected " +
                "${migratedOrderIds.size} positions, found $positionCount."
        }

        val dbReserved = sumOpenPositionReserved(mainDb)
        val dbRealized = sumClosedOrderRealized(mainDb)
        require(kotlin.math.abs(account.reservedMargin - dbReserved) <= EPSILON) {
            "Migration verification failed: reserved margin mismatch " +
                "(${account.reservedMargin} vs $dbReserved)."
        }
        require(kotlin.math.abs(account.realizedPnl - dbRealized) <= EPSILON) {
            "Migration verification failed: realized P&L mismatch " +
                "(${account.realizedPnl} vs $dbRealized)."
        }
        require(account.balance.isFinite() && account.startingBalance.isFinite()) {
            "Migration verification failed: invalid account balance."
        }
    }

    private fun sumOpenPositionReserved(db: SQLiteDatabase): Double {
        db.rawQuery(
            "SELECT COALESCE(SUM(reserved_margin), 0) " +
                "FROM positions WHERE status = ?",
            arrayOf("OPEN")
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
        }
    }

    private fun sumClosedOrderRealized(db: SQLiteDatabase): Double {
        db.rawQuery(
            "SELECT COALESCE(SUM(realized_pnl), 0) " +
                "FROM paper_orders WHERE status = ?",
            arrayOf("CLOSED")
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
        }
    }

    private fun countMigratedOrders(db: SQLiteDatabase): Int {
        db.rawQuery(
            "SELECT COUNT(*) FROM paper_orders " +
                "WHERE legacy_order_id IS NOT NULL",
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun countPositionsForOrders(
        db: SQLiteDatabase,
        orderIds: List<Long>
    ): Int {
        if (orderIds.isEmpty()) return 0
        val placeholders = orderIds.joinToString(",") { "?" }
        val args = orderIds.map { it.toString() }.toTypedArray()
        db.rawQuery(
            "SELECT COUNT(*) FROM positions WHERE order_id IN ($placeholders)",
            args
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun findOrderIdByLegacyId(
        db: SQLiteDatabase,
        legacyOrderId: Long
    ): Long? {
        db.query(
            "paper_orders",
            arrayOf("id"),
            "legacy_order_id = ?",
            arrayOf(legacyOrderId.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun readLegacyOrders(
        db: SQLiteDatabase,
        shortMarginRate: Double
    ): List<LegacyOrder> {
        if (!tableExists(db, "paper_orders")) return emptyList()

        val hasReservedMargin = columnExists(
            db,
            "paper_orders",
            "reserved_margin"
        )
        val result = ArrayList<LegacyOrder>()

        db.rawQuery("SELECT * FROM paper_orders ORDER BY id ASC", null)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(
                        cursor.getColumnIndexOrThrow("id")
                    )
                    val symbol = cursor.getString(
                        cursor.getColumnIndexOrThrow("symbol")
                    ).trim().uppercase(Locale.ROOT)
                    val side = cursor.getString(
                        cursor.getColumnIndexOrThrow("side")
                    ).trim().uppercase(Locale.ROOT)
                    val quantity = cursor.getInt(
                        cursor.getColumnIndexOrThrow("quantity")
                    )
                    val entryPrice = cursor.getDouble(
                        cursor.getColumnIndexOrThrow("entry_price")
                    )
                    val status = cursor.getString(
                        cursor.getColumnIndexOrThrow("status")
                    ).trim().uppercase(Locale.ROOT)
                    val exitPrice = nullableDouble(cursor, "exit_price")
                    val realizedPnl = nullableDouble(cursor, "realized_pnl")
                    val createdAt = cursor.getLong(
                        cursor.getColumnIndexOrThrow("created_at")
                    )
                    val updatedAt = cursor.getLong(
                        cursor.getColumnIndexOrThrow("updated_at")
                    )

                    require(id > 0L) { "Legacy order id is invalid." }
                    require(symbol.isNotBlank()) {
                        "Legacy order $id has an empty symbol."
                    }
                    require(side == "BUY" || side == "SELL") {
                        "Legacy order $id has an invalid side: $side"
                    }
                    require(status == "OPEN" || status == "CLOSED") {
                        "Legacy order $id has an invalid status: $status"
                    }
                    require(quantity > 0) {
                        "Legacy order $id has an invalid quantity."
                    }
                    require(entryPrice.isFinite() && entryPrice > 0.0) {
                        "Legacy order $id has an invalid entry price."
                    }
                    require(exitPrice == null ||
                        (exitPrice.isFinite() && exitPrice > 0.0)
                    ) {
                        "Legacy order $id has an invalid exit price."
                    }
                    require(realizedPnl == null || realizedPnl.isFinite()) {
                        "Legacy order $id has an invalid realized P&L."
                    }

                    val storedReserved = if (hasReservedMargin) {
                        nullableDouble(cursor, "reserved_margin") ?: 0.0
                    } else {
                        0.0
                    }
                    val reserved = if (status == "CLOSED") {
                        0.0
                    } else if (hasReservedMargin) {
                        storedReserved
                    } else if (side == "SELL") {
                        entryPrice * quantity * shortMarginRate
                    } else {
                        0.0
                    }
                    require(reserved.isFinite() && reserved >= 0.0) {
                        "Legacy order $id has an invalid reserved margin."
                    }

                    result.add(
                        LegacyOrder(
                            id = id,
                            symbol = symbol,
                            side = side,
                            quantity = quantity,
                            entryPrice = entryPrice,
                            exitPrice = exitPrice,
                            realizedPnl = realizedPnl,
                            reservedMargin = reserved,
                            status = status,
                            createdAt = createdAt,
                            updatedAt = updatedAt
                        )
                    )
                }
            }

        return result
    }

    private fun readLegacyBalance(db: SQLiteDatabase): Double? {
        if (!tableExists(db, "paper_account")) return null
        db.query(
            "paper_account",
            arrayOf("balance"),
            "id = ?",
            arrayOf("1"),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val balance = cursor.getDouble(0)
            require(balance.isFinite()) {
                "Legacy paper balance is invalid."
            }
            return balance
        }
    }

    private fun readMainAccount(db: SQLiteDatabase): AccountSnapshot? {
        db.query(
            "paper_account",
            arrayOf(
                "starting_balance",
                "balance",
                "reserved_margin",
                "realized_pnl",
                "updated_at"
            ),
            "id = ?",
            arrayOf("1"),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return AccountSnapshot(
                startingBalance = cursor.getDouble(0),
                balance = cursor.getDouble(1),
                reservedMargin = cursor.getDouble(2),
                realizedPnl = cursor.getDouble(3),
                updatedAt = cursor.getLong(4)
            )
        }
    }

    private fun writeMarker(
        db: SQLiteDatabase,
        value: String,
        now: Long
    ) {
        val values = ContentValues().apply {
            put("key", MARKER_KEY)
            put("value", value)
            put("updated_at", now)
        }
        db.insertWithOnConflict(
            "app_state",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun nullableDouble(cursor: Cursor, column: String): Double? {
        val index = cursor.getColumnIndex(column)
        if (index < 0 || cursor.isNull(index)) return null
        return cursor.getDouble(index)
    }

    private fun countTableRows(db: SQLiteDatabase, table: String): Int {
        if (!tableExists(db, table)) return 0
        db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean {
        db.rawQuery(
            "SELECT name FROM sqlite_master " +
                "WHERE type = 'table' AND name = ?",
            arrayOf(table)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun columnExists(
        db: SQLiteDatabase,
        table: String,
        column: String
    ): Boolean {
        if (!tableExists(db, table)) return false
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) return false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex)
                        .equals(column, ignoreCase = true)
                ) {
                    return true
                }
            }
        }
        return false
    }

    private enum class AccountMode {
        REPLACE,
        MERGE,
        KEEP
    }

    private data class AccountSnapshot(
        val startingBalance: Double,
        val balance: Double,
        val reservedMargin: Double,
        val realizedPnl: Double,
        val updatedAt: Long
    )

    private data class LegacyOrder(
        val id: Long,
        val symbol: String,
        val side: String,
        val quantity: Int,
        val entryPrice: Double,
        val exitPrice: Double?,
        val realizedPnl: Double?,
        val reservedMargin: Double,
        val status: String,
        val createdAt: Long,
        val updatedAt: Long
    )
}
