package com.sb.backend.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.sb.backend.core.AppConfig
import com.sb.backend.models.OrderSide
import com.sb.backend.models.OrderStatus
import com.sb.backend.trading.PaperSignalPolicy
import java.io.File

/**
 * Selectively removes test-generated paper records while preserving genuine
 * signal-linked records and records whose provenance cannot be proven.
 */
object PaperLegacyCleanup {

    const val MARKER_KEY = "paper_legacy_cleanup_v2"

    private const val SUPPRESSED_MIGRATION_VALUE = "suppressed_by_cleanup_v2"
    private const val DELETE_CHUNK_SIZE = 500

    data class Result(
        val applied: Boolean,
        val skipped: Boolean,
        val backupFile: File?,
        val deletedOrders: Int,
        val deletedPositions: Int,
        val deletedExecutions: Int,
        val deletedEvents: Int,
        val preservedOrders: Int,
        val preservedPositions: Int,
        val preservedExecutions: Int,
        val preservedEvents: Int,
        val uncertainOrders: Int,
        val uncertainPositions: Int,
        val uncertainExecutions: Int,
        val uncertainEvents: Int,
        val message: String
    )

    fun cleanup(
        context: Context,
        databaseHelper: SBDatabaseHelper,
        now: Long = System.currentTimeMillis()
    ): Result {
        val db = databaseHelper.writableDatabase
        val initialPlan = buildPlan(db)
        val backupFile = if (initialPlan.hasDestructiveRows) {
            DatabaseBackup(context, databaseHelper)
                .backup("sb_paper_legacy_cleanup")
                ?: return skipped(
                    message = "Cleanup skipped: database backup failed."
                )
        } else {
            null
        }

        db.beginTransaction()
        try {
            val plan = buildPlan(db)
            if (plan.hasDestructiveRows && backupFile == null) {
                throw IllegalStateException(
                    "Test-generated paper rows appeared during cleanup."
                )
            }

            val deletedExecutions = deleteByIds(
                db,
                SBDatabaseHelper.TABLE_PAPER_EXECUTIONS,
                plan.testExecutionIds
            )
            val deletedEvents = deleteByIds(
                db,
                SBDatabaseHelper.TABLE_PAPER_EVENTS,
                plan.testEventIds
            )
            val deletedPositions = deleteByIds(
                db,
                SBDatabaseHelper.TABLE_POSITIONS,
                plan.testPositionIds
            )
            val deletedOrders = deleteByIds(
                db,
                SBDatabaseHelper.TABLE_PAPER_ORDERS,
                plan.testOrderIds
            )

            val startingBalance = readStartingBalance(db)
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: AppConfig.get().paperStartingBalance
            rebuildAccount(
                db = db,
                startingBalance = startingBalance,
                now = now
            )

            writeMarker(
                db = db,
                value = "v2|backup=${backupFile?.name.orEmpty()}" +
                    "|orders=$deletedOrders" +
                    "|positions=$deletedPositions" +
                    "|executions=$deletedExecutions" +
                    "|events=$deletedEvents" +
                    "|preserved=${plan.preservedOrderCount}" +
                    "|uncertain=${plan.uncertainOrderCount}" +
                    "|starting=$startingBalance",
                now = now
            )
            if (plan.hasDestructiveRows) {
                suppressLegacyMigration(db, now)
            }

            db.setTransactionSuccessful()
            return Result(
                applied = plan.hasDestructiveRows,
                skipped = !plan.hasDestructiveRows,
                backupFile = backupFile,
                deletedOrders = deletedOrders,
                deletedPositions = deletedPositions,
                deletedExecutions = deletedExecutions,
                deletedEvents = deletedEvents,
                preservedOrders = plan.preservedOrderCount,
                preservedPositions = plan.preservedPositionCount,
                preservedExecutions = plan.preservedExecutionCount,
                preservedEvents = plan.preservedEventCount,
                uncertainOrders = plan.uncertainOrderCount,
                uncertainPositions = plan.uncertainPositionCount,
                uncertainExecutions = plan.uncertainExecutionCount,
                uncertainEvents = plan.uncertainEventCount,
                message = if (plan.hasDestructiveRows) {
                    "Paper legacy cleanup removed test-generated records " +
                        "and preserved signal provenance."
                } else {
                    "No test-generated paper rows found; account reconciled."
                }
            )
        } finally {
            db.endTransaction()
        }
    }

    fun readMarker(db: SQLiteDatabase): String? {
        if (!tableExists(db, SBDatabaseHelper.TABLE_APP_STATE)) return null
        db.query(
            SBDatabaseHelper.TABLE_APP_STATE,
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

    private fun buildPlan(db: SQLiteDatabase): CleanupPlan {
        val orders = readOrders(db)
        val orderClasses = orders.associate { order ->
            order.id to PaperLegacyClassifier.classifyOrder(
                signalId = order.signalId,
                entrySource = order.entrySource
            )
        }
        val testOrderIds = orderClasses.filterValues {
            it == PaperRecordClass.TEST_GENERATED
        }.keys
        var genuineOrders = 0
        var uncertainOrders = 0
        orderClasses.values.forEach { recordClass ->
            when (recordClass) {
                PaperRecordClass.GENUINE_SIGNAL -> genuineOrders++
                PaperRecordClass.UNCERTAIN -> uncertainOrders++
                PaperRecordClass.TEST_GENERATED -> Unit
            }
        }

        val positions = readChildRows(
            db,
            SBDatabaseHelper.TABLE_POSITIONS,
            includeSignalId = false
        )
        val testPositionIds = ArrayList<Long>()
        var genuinePositions = 0
        var uncertainPositions = 0
        positions.forEach { row ->
            val recordClass = PaperLegacyClassifier.classifyChild(
                parentClass = row.orderId?.let { orderClasses[it] },
                childSignalId = row.signalId
            )
            when (recordClass) {
                PaperRecordClass.TEST_GENERATED ->
                    testPositionIds.add(row.id)
                PaperRecordClass.GENUINE_SIGNAL -> genuinePositions++
                PaperRecordClass.UNCERTAIN -> uncertainPositions++
            }
        }

        val executions = readChildRows(
            db,
            SBDatabaseHelper.TABLE_PAPER_EXECUTIONS
        )
        val testExecutionIds = ArrayList<Long>()
        var genuineExecutions = 0
        var uncertainExecutions = 0
        executions.forEach { row ->
            val recordClass = PaperLegacyClassifier.classifyChild(
                parentClass = row.orderId?.let { orderClasses[it] },
                childSignalId = row.signalId
            )
            when (recordClass) {
                PaperRecordClass.TEST_GENERATED ->
                    testExecutionIds.add(row.id)
                PaperRecordClass.GENUINE_SIGNAL -> genuineExecutions++
                PaperRecordClass.UNCERTAIN -> uncertainExecutions++
            }
        }

        val events = readChildRows(db, SBDatabaseHelper.TABLE_PAPER_EVENTS)
        val testEventIds = ArrayList<Long>()
        var genuineEvents = 0
        var uncertainEvents = 0
        events.forEach { row ->
            val recordClass = PaperLegacyClassifier.classifyChild(
                parentClass = row.orderId?.let { orderClasses[it] },
                childSignalId = row.signalId
            )
            when (recordClass) {
                PaperRecordClass.TEST_GENERATED -> testEventIds.add(row.id)
                PaperRecordClass.GENUINE_SIGNAL -> genuineEvents++
                PaperRecordClass.UNCERTAIN -> uncertainEvents++
            }
        }

        return CleanupPlan(
            testOrderIds = testOrderIds,
            testPositionIds = testPositionIds,
            testExecutionIds = testExecutionIds,
            testEventIds = testEventIds,
            preservedOrderCount = genuineOrders + uncertainOrders,
            preservedPositionCount = genuinePositions + uncertainPositions,
            preservedExecutionCount = genuineExecutions + uncertainExecutions,
            preservedEventCount = genuineEvents + uncertainEvents,
            uncertainOrderCount = uncertainOrders,
            uncertainPositionCount = uncertainPositions,
            uncertainExecutionCount = uncertainExecutions,
            uncertainEventCount = uncertainEvents
        )
    }

    private fun rebuildAccount(
        db: SQLiteDatabase,
        startingBalance: Double,
        now: Long
    ) {
        val orders = readOrders(db)
        var balance = startingBalance
        var reservedMargin = 0.0
        var realizedPnl = 0.0

        orders.forEach { order ->
            val executions = readExecutionsForOrder(db, order.id)
            val entryExecution = executions.firstOrNull {
                it.kind == "ENTRY"
            }
            val entered = entryExecution != null ||
                order.entryTimestamp != null ||
                order.status == OrderStatus.OPEN.name ||
                order.status == OrderStatus.CLOSED.name
            val entryPrice = entryExecution?.price ?: order.entryPrice
            val entryQuantity = entryExecution?.quantity ?: order.quantity
            val entryCharge = when {
                order.entryCharges > 0.0 -> order.entryCharges
                entryExecution != null -> entryExecution.charges
                else -> 0.0
            }

            if (entered) {
                if (order.side == OrderSide.BUY.name) {
                    balance -= entryPrice * entryQuantity + entryCharge
                } else {
                    balance -= entryCharge
                }
                realizedPnl -= entryCharge
            }

            val exits = executions.filter { it.kind != "ENTRY" }
            if (exits.isNotEmpty()) {
                exits.forEach { execution ->
                    val allocatedEntryCharge =
                        PaperSignalPolicy.allocatedEntryCharge(
                            totalEntryCharge = entryCharge,
                            originalQuantity = entryQuantity,
                            exitQuantity = execution.quantity
                        )
                    val exitCharge = if (execution.charges.isFinite()) {
                        (execution.charges - allocatedEntryCharge)
                            .coerceAtLeast(0.0)
                    } else {
                        PaperSignalPolicy.exitCharge(
                            price = execution.price,
                            quantity = execution.quantity,
                            costBps = order.costBpsPerSide
                        )
                    }
                    if (order.side == OrderSide.BUY.name) {
                        balance += execution.price * execution.quantity -
                            exitCharge
                    } else {
                        balance += execution.grossPnl - exitCharge
                    }
                    realizedPnl += execution.grossPnl - exitCharge
                }
            } else if (entered &&
                order.status == OrderStatus.CLOSED.name &&
                order.exitPrice != null
            ) {
                val gross = PaperSignalPolicy.grossPnl(
                    side = order.side,
                    entryPrice = entryPrice,
                    exitPrice = order.exitPrice,
                    quantity = entryQuantity
                )
                val exitCharge = PaperSignalPolicy.exitCharge(
                    price = order.exitPrice,
                    quantity = entryQuantity,
                    costBps = order.costBpsPerSide
                )
                if (order.side == OrderSide.BUY.name) {
                    balance += order.exitPrice * entryQuantity - exitCharge
                } else {
                    balance += gross - exitCharge
                }
                realizedPnl += gross - exitCharge
            }

            if (order.status == OrderStatus.PENDING.name) {
                val requirement = PaperSignalPolicy.capitalRequirement(
                    side = order.side,
                    price = order.entryPrice,
                    quantity = order.quantity,
                    costBpsPerSide = order.costBpsPerSide,
                    shortMarginRate = AppConfig.get().shortMarginRate
                )
                val requiredReservation = if (order.reservedMargin > 0.0) {
                    order.reservedMargin
                } else {
                    requirement.totalReservation
                }
                reservedMargin += requiredReservation
                if (order.reservedMargin <= 0.0 &&
                    requiredReservation > 0.0
                ) {
                    val values = ContentValues().apply {
                        put("reserved_margin", requiredReservation)
                        put("updated_at", now)
                    }
                    db.update(
                        SBDatabaseHelper.TABLE_PAPER_ORDERS,
                        values,
                        "id = ? AND status = ?",
                        arrayOf(
                            order.id.toString(),
                            OrderStatus.PENDING.name
                        )
                    )
                }
            } else if (order.status == OrderStatus.OPEN.name) {
                reservedMargin += order.reservedMargin
            }
        }

        require(balance.isFinite() && realizedPnl.isFinite() &&
            reservedMargin.isFinite() && reservedMargin >= 0.0) {
            "Paper account rebuild produced invalid values."
        }
        val accountValues = ContentValues().apply {
            put("id", 1)
            put("starting_balance", startingBalance)
            put("balance", balance)
            put("reserved_margin", reservedMargin)
            put("realized_pnl", realizedPnl)
            put("updated_at", now)
        }
        db.insertWithOnConflict(
            SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
            null,
            accountValues,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun readOrders(db: SQLiteDatabase): List<OrderSnapshot> {
        val result = ArrayList<OrderSnapshot>()
        db.query(
            SBDatabaseHelper.TABLE_PAPER_ORDERS,
            arrayOf(
                "id",
                "signal_id",
                "entry_source",
                "side",
                "quantity",
                "entry_price",
                "exit_price",
                "reserved_margin",
                "status",
                "entry_timestamp",
                "entry_charges",
                "cost_bps_per_side"
            ),
            null,
            null,
            null,
            null,
            "id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    OrderSnapshot(
                        id = cursor.getLong(0),
                        signalId = cursor.getLongOrNull(1),
                        entrySource = cursor.getString(2),
                        side = cursor.getString(3),
                        quantity = cursor.getInt(4),
                        entryPrice = cursor.getDouble(5),
                        exitPrice = cursor.getDoubleOrNull(6),
                        reservedMargin = cursor.getDouble(7),
                        status = cursor.getString(8),
                        entryTimestamp = cursor.getLongOrNull(9),
                        entryCharges = cursor.getDouble(10),
                        costBpsPerSide = cursor.getDouble(11)
                    )
                )
            }
        }
        return result
    }

    private fun readChildRows(
        db: SQLiteDatabase,
        table: String,
        includeSignalId: Boolean = true
    ): List<ChildRow> {
        val result = ArrayList<ChildRow>()
        val projection = if (includeSignalId) {
            arrayOf("id", "order_id", "signal_id")
        } else {
            arrayOf("id", "order_id", "NULL AS signal_id")
        }
        db.query(
            table,
            projection,
            null,
            null,
            null,
            null,
            "id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    ChildRow(
                        id = cursor.getLong(0),
                        orderId = cursor.getLongOrNull(1),
                        signalId = cursor.getLongOrNull(2)
                    )
                )
            }
        }
        return result
    }

    private fun readExecutionsForOrder(
        db: SQLiteDatabase,
        orderId: Long
    ): List<ExecutionSnapshot> {
        val result = ArrayList<ExecutionSnapshot>()
        db.query(
            SBDatabaseHelper.TABLE_PAPER_EXECUTIONS,
            arrayOf(
                "kind",
                "quantity",
                "price",
                "gross_pnl",
                "charges"
            ),
            "order_id = ?",
            arrayOf(orderId.toString()),
            null,
            null,
            "executed_at ASC, id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    ExecutionSnapshot(
                        kind = cursor.getString(0),
                        quantity = cursor.getInt(1),
                        price = cursor.getDouble(2),
                        grossPnl = cursor.getDouble(3),
                        charges = cursor.getDouble(4)
                    )
                )
            }
        }
        return result
    }

    private fun readStartingBalance(db: SQLiteDatabase): Double? {
        if (!tableExists(db, SBDatabaseHelper.TABLE_PAPER_ACCOUNT)) return null
        db.query(
            SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
            arrayOf("starting_balance"),
            "id = ?",
            arrayOf("1"),
            null,
            null,
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getDouble(0) else null
        }
    }

    private fun suppressLegacyMigration(
        db: SQLiteDatabase,
        now: Long
    ) {
        if (LegacyPaperMigration.readMarker(db) != null) return
        val values = ContentValues().apply {
            put("key", LegacyPaperMigration.MARKER_KEY)
            put("value", SUPPRESSED_MIGRATION_VALUE)
            put("updated_at", now)
        }
        db.insertWithOnConflict(
            SBDatabaseHelper.TABLE_APP_STATE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
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
            SBDatabaseHelper.TABLE_APP_STATE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun deleteByIds(
        db: SQLiteDatabase,
        table: String,
        ids: Collection<Long>
    ): Int {
        if (ids.isEmpty()) return 0
        var deleted = 0
        ids.chunked(DELETE_CHUNK_SIZE).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            deleted += db.delete(
                table,
                "id IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray()
            )
        }
        return deleted
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

    private fun skipped(message: String): Result {
        return Result(
            applied = false,
            skipped = true,
            backupFile = null,
            deletedOrders = 0,
            deletedPositions = 0,
            deletedExecutions = 0,
            deletedEvents = 0,
            preservedOrders = 0,
            preservedPositions = 0,
            preservedExecutions = 0,
            preservedEvents = 0,
            uncertainOrders = 0,
            uncertainPositions = 0,
            uncertainExecutions = 0,
            uncertainEvents = 0,
            message = message
        )
    }

    private fun android.database.Cursor.getLongOrNull(index: Int): Long? {
        return if (isNull(index)) null else getLong(index)
    }

    private fun android.database.Cursor.getDoubleOrNull(index: Int): Double? {
        return if (isNull(index)) null else getDouble(index)
    }

    private data class ChildRow(
        val id: Long,
        val orderId: Long?,
        val signalId: Long?
    )

    private data class OrderSnapshot(
        val id: Long,
        val signalId: Long?,
        val entrySource: String?,
        val side: String,
        val quantity: Int,
        val entryPrice: Double,
        val exitPrice: Double?,
        val reservedMargin: Double,
        val status: String,
        val entryTimestamp: Long?,
        val entryCharges: Double,
        val costBpsPerSide: Double
    )

    private data class ExecutionSnapshot(
        val kind: String,
        val quantity: Int,
        val price: Double,
        val grossPnl: Double,
        val charges: Double
    )

    private data class CleanupPlan(
        val testOrderIds: Collection<Long>,
        val testPositionIds: Collection<Long>,
        val testExecutionIds: Collection<Long>,
        val testEventIds: Collection<Long>,
        val preservedOrderCount: Int,
        val preservedPositionCount: Int,
        val preservedExecutionCount: Int,
        val preservedEventCount: Int,
        val uncertainOrderCount: Int,
        val uncertainPositionCount: Int,
        val uncertainExecutionCount: Int,
        val uncertainEventCount: Int
    ) {
        val hasDestructiveRows: Boolean
            get() = testOrderIds.isNotEmpty() ||
                testPositionIds.isNotEmpty() ||
                testExecutionIds.isNotEmpty() ||
                testEventIds.isNotEmpty()
    }
}
